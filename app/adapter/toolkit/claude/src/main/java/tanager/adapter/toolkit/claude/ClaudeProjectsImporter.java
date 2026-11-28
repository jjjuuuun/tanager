package tanager.adapter.toolkit.claude;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.application.port.out.ConversationError;
import tanager.application.port.out.ConversationScan;
import tanager.application.port.out.ConversationSource;
import tanager.domain.history.ConversationId;
import tanager.domain.history.PastConversation;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ShellLine;
import tanager.domain.shared.Result;

/**
 * Claude Code 의 대화 기록({@code ~/.claude/projects/<인코딩된 경로>/<세션ID>.jsonl})을 읽는다(FR-TK-09, ADR-0020). 폴더 이름의 인코딩은
 * 되돌릴 수 없으므로 파일 안의 {@code cwd} 를 쓴다. 파일마다 앞부분({@link TranscriptHead#MAX_LINES} 줄 · {@link
 * TranscriptHead#MAX_BYTES} 바이트)만 읽고, 파일을 쓰지 않는다. 읽은 내용(요약 포함)은 로그에 남기지 않는다 — 로그는 파일 수 · 건너뛴 수 · 걸린
 * 시간뿐이다.
 */
public final class ClaudeProjectsImporter implements ConversationSource {

    private static final System.Logger LOG = System.getLogger(ClaudeProjectsImporter.class.getName());
    private static final String EXTENSION = ".jsonl";

    private final TranscriptOpener opener;

    /** {@code ServiceLoader} 용. */
    public ClaudeProjectsImporter() {
        this(Files::newInputStream);
    }

    ClaudeProjectsImporter(TranscriptOpener opener) {
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    @Override
    public VisitSource source() {
        return VisitSource.CLAUDE_CODE;
    }

    @Override
    public boolean supports(ShellEnvironment env) {
        return switch (env) {
            case ShellEnvironment.Windows _ -> true;
            case ShellEnvironment.Wsl _ -> true;
        };
    }

    @Override
    public Result<ConversationScan, ConversationError> scan(ShellEnvironment env, Path hostHome) {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(hostHome, "hostHome");
        long started = System.nanoTime();
        Path projects = hostHome.resolve(".claude").resolve("projects");
        if (!Files.isDirectory(projects)) {
            return Result.err(new ConversationError.NoHistory());
        }
        List<Path> files;
        try {
            files = transcripts(projects);
        } catch (IOException e) {
            LOG.log(
                    Level.WARNING,
                    "Claude 대화 기록 폴더 목록을 읽지 못했다: {0}",
                    e.getClass().getSimpleName());
            return Result.err(new ConversationError.Unreadable());
        }
        Tally tally = new Tally();
        List<PastConversation> conversations = new ArrayList<>();
        for (Path file : files) {
            read(env, file, tally).ifPresent(conversations::add);
        }
        conversations.sort(
                Comparator.comparing(PastConversation::lastActive).reversed().thenComparing(c -> c.id().value()));
        LOG.log(
                Level.DEBUG,
                "Claude 대화 기록: 파일 {0} 개, 대화 {1} 개, 건너뜀 {2} 개, {3} ms",
                files.size(),
                conversations.size(),
                tally.skipped,
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        if (tally.opened == 0 && tally.unreadable > 0) {
            return Result.err(new ConversationError.Unreadable());
        }
        if (tally.opened > 0 && tally.withCwd == 0) {
            return Result.err(new ConversationError.UnknownFormat());
        }
        return Result.ok(new ConversationScan(conversations, tally.skipped));
    }

    @Override
    public ShellLine resume(ConversationId id) {
        return new ShellLine("claude --resume " + id.value());
    }

    @Override
    public ShellLine continueLatest() {
        return new ShellLine("claude --continue");
    }

    // projects/*/*.jsonl 만. 하위 폴더(<세션ID>/subagents/ 등)와 projects/ 바로 아래 파일은 보지 않는다.
    private static List<Path> transcripts(Path projects) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(projects, Files::isDirectory)) {
            for (Path folder : folders) {
                try (DirectoryStream<Path> inFolder = Files.newDirectoryStream(
                        folder, p -> p.getFileName().toString().endsWith(EXTENSION) && Files.isRegularFile(p))) {
                    for (Path file : inFolder) {
                        files.add(file);
                    }
                }
            }
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private Optional<PastConversation> read(ShellEnvironment env, Path file, Tally tally) {
        String name = file.getFileName().toString();
        Optional<ConversationId> id = ConversationId.parse(name.substring(0, name.length() - EXTENSION.length()));
        if (id.isEmpty()) {
            tally.skipped++;
            return Optional.empty();
        }
        TranscriptHead head;
        Instant modified;
        try {
            head = TranscriptHead.read(opener.open(file));
            modified = Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            tally.unreadable++;
            tally.skipped++;
            return Optional.empty();
        }
        tally.opened++;
        if (head.cwd().isEmpty()) {
            tally.skipped++;
            return Optional.empty();
        }
        tally.withCwd++;
        Optional<WorkspacePath> cwd = WorkspacePath.of(env, head.cwd().get()).value();
        if (cwd.isEmpty()) {
            tally.skipped++;
            return Optional.empty();
        }
        return Optional.of(new PastConversation(VisitSource.CLAUDE_CODE, id.get(), cwd.get(), head.title(), modified));
    }

    /** 한 번의 scan 안에서만 쓰는 셈. */
    private static final class Tally {
        int opened;
        int unreadable;
        int withCwd;
        int skipped;
    }
}
