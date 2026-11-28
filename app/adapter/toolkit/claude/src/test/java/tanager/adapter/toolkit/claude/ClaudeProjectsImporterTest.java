package tanager.adapter.toolkit.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static tanager.adapter.toolkit.claude.ClaudeHome.assistant;
import static tanager.adapter.toolkit.claude.ClaudeHome.meta;
import static tanager.adapter.toolkit.claude.ClaudeHome.snapshot;
import static tanager.adapter.toolkit.claude.ClaudeHome.summary;
import static tanager.adapter.toolkit.claude.ClaudeHome.toolResult;
import static tanager.adapter.toolkit.claude.ClaudeHome.user;
import static tanager.adapter.toolkit.claude.ClaudeHome.userBlocks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

class ClaudeProjectsImporterTest {

    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();
    private static final String ID_1 = "11111111-1111-4111-8111-111111111111";
    private static final String ID_2 = "22222222-2222-4222-8222-222222222222";
    private static final String ID_3 = "33333333-3333-4333-8333-333333333333";
    private static final Instant T1 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-02T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-03T10:00:00Z");

    @TempDir
    Path tmp;

    private ClaudeHome home;

    @BeforeEach
    void setUp() {
        home = new ClaudeHome(tmp);
    }

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static ConversationScan ok(Result<ConversationScan, ConversationError> result) {
        assertThat(result.error()).isEmpty();
        return result.value().orElseThrow();
    }

    private ConversationScan scanWsl() {
        return ok(new ClaudeProjectsImporter().scan(WSL, home.home()));
    }

    private static PastConversation only(ConversationScan scan) {
        assertThat(scan.conversations()).hasSize(1);
        return scan.conversations().getFirst();
    }

    @Test
    @DisplayName("픽스처 홈(폴더 2개 · 대화 3개)에서 대화마다 cwd · 요약 · 수정 시각을 읽고 최근 것부터 준다")
    void scansFixtureHome() {
        // given
        home.conversation("-home-dev-alpha", ID_1 + ".jsonl", T1, user("/home/dev/alpha", "Alpha first task"));
        home.conversation(
                "-home-dev-alpha",
                ID_2 + ".jsonl",
                T3,
                snapshot(),
                user("/home/dev/alpha", "Alpha second task"),
                assistant("/home/dev/alpha"));
        home.conversation("-home-dev-beta", ID_3 + ".jsonl", T2, user("/home/dev/beta", "베타 작업"));

        // when
        ConversationScan scan = scanWsl();

        // then
        assertThat(scan.skippedFiles()).isZero();
        assertThat(scan.conversations())
                .containsExactly(
                        new PastConversation(
                                VisitSource.CLAUDE_CODE,
                                new ConversationId(ID_2),
                                path(WSL, "/home/dev/alpha"),
                                Optional.of("Alpha second task"),
                                T3),
                        new PastConversation(
                                VisitSource.CLAUDE_CODE,
                                new ConversationId(ID_3),
                                path(WSL, "/home/dev/beta"),
                                Optional.of("베타 작업"),
                                T2),
                        new PastConversation(
                                VisitSource.CLAUDE_CODE,
                                new ConversationId(ID_1),
                                path(WSL, "/home/dev/alpha"),
                                Optional.of("Alpha first task"),
                                T1));
    }

    @Test
    @DisplayName("첫 줄이 cwd 없는 summary 여도 셋째 줄의 cwd 를 찾는다")
    void findsCwdOnThirdLine() {
        // given
        home.conversation(
                "-home-dev-alpha", ID_1 + ".jsonl", T1, summary(), snapshot(), user("/home/dev/alpha", "Do the thing"));

        // when
        PastConversation conversation = only(scanWsl());

        // then
        assertThat(conversation.cwd()).isEqualTo(path(WSL, "/home/dev/alpha"));
        assertThat(conversation.title()).contains("Do the thing");
    }

    @Test
    @DisplayName("isMeta · <command-name> · <local-command-stdout> · tool_result 줄 뒤의 진짜 첫 메시지가 요약이다(익명화 픽스처)")
    void skipsMetaCommandsAndToolResults() {
        // given 실제 기록의 줄 종류 · 키 모양을 따른 합성 픽스처
        home.resource("-home-dev-sample-project", ID_1 + ".jsonl", T1, "command-then-message.jsonl");

        // when
        PastConversation conversation = only(scanWsl());

        // then
        assertThat(conversation.cwd()).isEqualTo(path(WSL, "/home/dev/sample-project"));
        assertThat(conversation.title()).contains("Add a synthetic feature to the sample project");
    }

    @Test
    @DisplayName("줄마다 만든 합성 줄로도 isMeta · 명령 기록 · tool_result 를 건너뛴다")
    void skipsNonPromptUserLines() {
        // given
        String cwd = "/home/dev/alpha";
        home.conversation(
                "-home-dev-alpha",
                ID_1 + ".jsonl",
                T1,
                meta(cwd, "meta text"),
                user(cwd, "<command-name>/clear</command-name>"),
                user(cwd, "  <local-command-stdout></local-command-stdout>"),
                toolResult(cwd, "tool output"),
                user(cwd, "the real prompt"));

        // when / then
        assertThat(only(scanWsl()).title()).contains("the real prompt");
    }

    @Test
    @DisplayName("content 가 블록 배열이면 text 블록을 이어 붙여 요약한다")
    void joinsTextBlocks() {
        // given
        home.conversation(
                "-home-dev-alpha",
                ID_1 + ".jsonl",
                T1,
                userBlocks("/home/dev/alpha", List.of("first part", "second part")));

        // when / then
        assertThat(only(scanWsl()).title()).contains("first part second part");
    }

    @Test
    @DisplayName("요약 원문 300자 · 줄바꿈 · 탭은 공백을 접어 80 코드 포인트(한글 포함)로 자른다")
    void truncatesLongSummary() {
        // given
        String raw = "abc 한글요청\n\t\t".repeat(28).substring(0, 300);
        home.conversation("-home-dev-alpha", ID_1 + ".jsonl", T1, user("/home/dev/alpha", raw));

        // when
        String title = only(scanWsl()).title().orElseThrow();

        // then 접으면 한 단위가 "abc 한글요청 "(9 코드 포인트) — 80번째는 '청'
        assertThat(title.codePointCount(0, title.length())).isEqualTo(80);
        assertThat(title).startsWith("abc 한글요청 abc 한글요청").endsWith("청").doesNotContain("\n", "\t", "  ");
    }

    @Test
    @DisplayName("사용자 메시지가 없는 대화는 제목이 empty 다")
    void conversationWithoutUserMessageHasNoTitle() {
        // given
        home.conversation(
                "-home-dev-alpha",
                ID_1 + ".jsonl",
                T1,
                assistant("/home/dev/alpha"),
                toolResult("/home/dev/alpha", "x"));

        // when
        PastConversation conversation = only(scanWsl());

        // then
        assertThat(conversation.title()).isEmpty();
        assertThat(conversation.cwd()).isEqualTo(path(WSL, "/home/dev/alpha"));
    }

    @Test
    @DisplayName("cwd 가 없는 파일 · 깨진 JSON 줄만 있는 파일은 건너뛰고 skippedFiles 가 센다")
    void skipsFilesWithoutCwd() {
        // given
        home.conversation("-home-dev-alpha", ID_1 + ".jsonl", T1, summary(), snapshot());
        home.conversation("-home-dev-alpha", ID_2 + ".jsonl", T1, "{not json", "[1, 2", "\"text\"", "");
        home.conversation("-home-dev-alpha", ID_3 + ".jsonl", T1, "{broken", user("/home/dev/alpha", "ok"));

        // when
        ConversationScan scan = scanWsl();

        // then
        assertThat(scan.skippedFiles()).isEqualTo(2);
        assertThat(scan.conversations()).extracting(PastConversation::id).containsExactly(new ConversationId(ID_3));
    }

    @Test
    @DisplayName("ConversationId 형식에 맞지 않는 파일 이름(..evil · 공백 · ;)은 건너뛰고 센다")
    void skipsBadFileNames() {
        // given
        String line = user("/home/dev/alpha", "x");
        home.conversation("-home-dev-alpha", "..evil.jsonl", T1, line);
        home.conversation("-home-dev-alpha", "a b.jsonl", T1, line);
        home.conversation("-home-dev-alpha", "x;rm -rf.jsonl", T1, line);
        home.conversation("-home-dev-alpha", ID_1 + ".jsonl", T1, line);

        // when
        ConversationScan scan = scanWsl();

        // then
        assertThat(scan.skippedFiles()).isEqualTo(3);
        assertThat(only(scan).id()).isEqualTo(new ConversationId(ID_1));
    }

    @Test
    @DisplayName("projects/*/*.jsonl 만 본다 — 하위 폴더(subagents 등)와 다른 확장자는 보지 않는다")
    void ignoresSubfoldersAndOtherFiles() throws IOException {
        // given
        String line = user("/home/dev/alpha", "x");
        home.conversation("-home-dev-alpha", ID_1 + ".jsonl", T1, line);
        home.conversation("-home-dev-alpha/" + ID_1 + "/subagents", "agent-a1.jsonl", T1, line);
        home.conversation("-home-dev-alpha", "notes.txt", T1, line);
        Files.writeString(home.projects().resolve("stray.jsonl"), line);

        // when
        ConversationScan scan = scanWsl();

        // then
        assertThat(scan.skippedFiles()).isZero();
        assertThat(only(scan).id()).isEqualTo(new ConversationId(ID_1));
    }

    @Test
    @DisplayName("10MB 대화 파일에서도 앞 256KB 넘게 읽지 않는다")
    void readsAtMost256KiB() {
        // given 디스크에는 자리만 두고, 열면 10MB 를 내는 가짜 스트림이 읽힌 바이트를 센다
        Path file = home.conversation("-home-dev-alpha", ID_1 + ".jsonl", T1, "");
        byte[] head = (assistant("/home/dev/alpha") + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] filler = (toolResult("/home/dev/alpha", "x".repeat(4000)) + "\n").getBytes(StandardCharsets.UTF_8);
        List<CountingStream> opened = new ArrayList<>();
        TranscriptOpener opener = f -> {
            assertThat(f).isEqualTo(file);
            CountingStream stream = new CountingStream(head, filler, 10L * 1024 * 1024);
            opened.add(stream);
            return stream;
        };

        // when
        ConversationScan scan = ok(new ClaudeProjectsImporter(opener).scan(WSL, home.home()));

        // then
        assertThat(opened).hasSize(1);
        assertThat(opened.getFirst().read).isPositive().isLessThanOrEqualTo(256 * 1024);
        assertThat(opened.getFirst().closed).isTrue();
        assertThat(only(scan).title()).isEmpty();
    }

    @Test
    @DisplayName("projects/ 가 없으면 NoHistory")
    void noProjectsIsNoHistory() throws IOException {
        // given
        Files.createDirectories(tmp.resolve(".claude"));

        // when
        var result = new ClaudeProjectsImporter().scan(WSL, home.home());

        // then
        assertThat(result.error()).containsInstanceOf(ConversationError.NoHistory.class);
    }

    @Test
    @DisplayName("projects/ 가 비어 있으면 빈 목록이다")
    void emptyProjectsIsEmptyScan() throws IOException {
        // given
        Files.createDirectories(home.projects());

        // when
        ConversationScan scan = scanWsl();

        // then
        assertThat(scan.conversations()).isEmpty();
        assertThat(scan.skippedFiles()).isZero();
    }

    @Test
    @DisplayName("파일은 있는데 어떤 파일에서도 cwd 를 못 찾으면 UnknownFormat")
    void noCwdAnywhereIsUnknownFormat() {
        // given
        home.conversation("-home-dev-alpha", ID_1 + ".jsonl", T1, summary());
        home.conversation("-home-dev-beta", ID_2 + ".jsonl", T1, "{broken");

        // when
        var result = new ClaudeProjectsImporter().scan(WSL, home.home());

        // then
        assertThat(result.error()).containsInstanceOf(ConversationError.UnknownFormat.class);
    }

    @Test
    @DisplayName("파일을 하나도 열지 못하면 Unreadable, 일부만 못 열면 그 파일만 건너뛴다")
    void unreadableFiles() {
        // given
        Path broken = home.conversation("-home-dev-alpha", ID_1 + ".jsonl", T1, user("/home/dev/alpha", "x"));
        home.conversation("-home-dev-alpha", ID_2 + ".jsonl", T2, user("/home/dev/alpha", "y"));
        TranscriptOpener failAll = f -> {
            throw new IOException("synthetic");
        };
        TranscriptOpener failOne = f -> {
            if (f.equals(broken)) {
                throw new IOException("synthetic");
            }
            return Files.newInputStream(f);
        };

        // when
        var all = new ClaudeProjectsImporter(failAll).scan(WSL, home.home());
        var one = ok(new ClaudeProjectsImporter(failOne).scan(WSL, home.home()));

        // then
        assertThat(all.error()).containsInstanceOf(ConversationError.Unreadable.class);
        assertThat(one.skippedFiles()).isEqualTo(1);
        assertThat(only(one).id()).isEqualTo(new ConversationId(ID_2));
    }

    @Test
    @DisplayName("요약에 든 문자열은 로그에 남지 않는다")
    void summaryIsNotLogged() {
        // given
        String secret = "SECRET-MARKER-208";
        home.conversation("-home-dev-alpha", ID_1 + ".jsonl", T1, user("/home/dev/alpha", "please " + secret));
        home.conversation("-home-dev-alpha", ID_2 + ".jsonl", T1, "{broken " + secret);
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(ClaudeProjectsImporter.class.getName());
        Level previous = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        ConversationScan scan;
        try {
            // when
            scan = scanWsl();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }

        // then
        assertThat(only(scan).title().orElseThrow()).contains(secret);
        assertThat(records).isNotEmpty().allSatisfy(record -> {
            assertThat(record.getMessage()).doesNotContain(secret);
            assertThat(Arrays.toString(Objects.requireNonNullElse(record.getParameters(), new Object[0])))
                    .doesNotContain(secret);
            assertThat(record.getThrown()).isNull();
        });
    }

    @Test
    @DisplayName("Windows 환경 픽스처(cwd C:\\work\\x)는 Windows WorkspacePath 가 된다")
    void windowsCwd() {
        // given
        home.conversation("C--work-x", ID_1 + ".jsonl", T1, user("C:\\work\\x", "win task"));

        // when
        PastConversation conversation = only(ok(new ClaudeProjectsImporter().scan(WINDOWS, home.home())));

        // then
        assertThat(conversation.cwd().env()).isEqualTo(WINDOWS);
        assertThat(conversation.cwd()).isEqualTo(path(WINDOWS, "c:\\WORK\\x"));
    }

    @Test
    @DisplayName("그 환경의 경로가 아닌 cwd(WSL 에서 C:\\…)는 건너뛰고 센다")
    void cwdOfOtherEnvironmentIsSkipped() {
        // given
        home.conversation("C--work-x", ID_1 + ".jsonl", T1, user("C:\\work\\x", "win task"));
        home.conversation("-home-dev-alpha", ID_2 + ".jsonl", T1, user("/home/dev/alpha", "wsl task"));

        // when
        ConversationScan scan = scanWsl();

        // then
        assertThat(scan.skippedFiles()).isEqualTo(1);
        assertThat(only(scan).id()).isEqualTo(new ConversationId(ID_2));
    }

    @Test
    @DisplayName("출처는 claude-code 이고 Windows · WSL 모두 지원한다")
    void sourceAndSupport() {
        // given
        var importer = new ClaudeProjectsImporter();
        // when / then
        assertThat(importer.source()).isEqualTo(VisitSource.CLAUDE_CODE);
        assertThat(importer.supports(WSL)).isTrue();
        assertThat(importer.supports(WINDOWS)).isTrue();
    }

    @Test
    @DisplayName("이어서 하기는 claude --resume <id>, 마지막 대화는 claude --continue 다(Enter 없음)")
    void resumeCommands() {
        // given
        var importer = new ClaudeProjectsImporter();
        // when / then
        assertThat(importer.resume(new ConversationId(ID_1))).isEqualTo(new ShellLine("claude --resume " + ID_1));
        assertThat(importer.continueLatest()).isEqualTo(new ShellLine("claude --continue"));
    }

    @Test
    @DisplayName("ServiceLoader 로 찾을 수 있다")
    void registeredAsService() {
        // given / when
        List<ConversationSource> sources = ServiceLoader.load(ConversationSource.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        // then
        assertThat(sources).hasAtLeastOneElementOfType(ClaudeProjectsImporter.class);
    }

    /** head 한 번, 그 뒤 filler 를 되풀이해 total 바이트를 내는 스트림. 읽힌 바이트를 센다. */
    private static final class CountingStream extends InputStream {
        private final byte[] head;
        private final byte[] filler;
        private final long total;
        long read;
        boolean closed;

        CountingStream(byte[] head, byte[] filler, long total) {
            this.head = head;
            this.filler = filler;
            this.total = total;
        }

        private byte at(long pos) {
            if (pos < head.length) {
                return head[(int) pos];
            }
            return filler[(int) ((pos - head.length) % filler.length)];
        }

        @Override
        public int read() {
            if (read >= total) {
                return -1;
            }
            return at(read++) & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (read >= total) {
                return -1;
            }
            int n = (int) Math.min(len, total - read);
            for (int i = 0; i < n; i++) {
                b[off + i] = at(read + i);
            }
            read += n;
            return n;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
