package tanager.adapter.toolkit.claude;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tanager.application.port.out.ScanStamp;
import tanager.application.port.out.ToolkitScan;
import tanager.domain.toolkit.ScopePrecedence;
import tanager.domain.toolkit.Shadowing;
import tanager.domain.toolkit.SourceRef;
import tanager.domain.toolkit.ToolkitItem;
import tanager.domain.toolkit.ToolkitWarning;

/**
 * 한 번의 scan 또는 지문 재기에서 읽기 클래스들이 파일에 닿는 유일한 통로. 본 폴더 · 파일과 그 수정 시각을 지문 입력으로 적고, 같은 실제 폴더를 두 번
 * 들어가지 않게 한다. 지문 재기({@link #stamping})는 같은 길을 걷되 파일 내용을 읽지 않는다 — 그래서 scan 과 지문의 입력이 늘 같다.
 *
 * <p>한 스레드에서 한 번만 쓰고 버린다.
 */
final class Collector {

    /** 파일 내용 읽기. 스트림은 {@link Collector} 가 닫는다. */
    @FunctionalInterface
    interface Parser<T> {
        T parse(InputStream in) throws IOException;
    }

    private final Optional<FileOpener> opener;
    private final Map<Path, Optional<Instant>> inputs = new LinkedHashMap<>();
    private final Set<Path> entered = new HashSet<>();
    private final List<ToolkitItem> items = new ArrayList<>();
    private final List<ToolkitWarning> warnings = new ArrayList<>();

    private Collector(Optional<FileOpener> opener) {
        this.opener = opener;
    }

    /** 파일 내용까지 읽어 항목 · 경고를 모은다. */
    static Collector scanning(FileOpener opener) {
        return new Collector(Optional.of(Objects.requireNonNull(opener, "opener")));
    }

    /** 폴더 목록과 수정 시각만 잰다. */
    static Collector stamping() {
        return new Collector(Optional.empty());
    }

    /** 폴더의 항목(이름 순). 폴더를 지문 입력으로 적는다 — 없는 폴더도(새로 생기면 지문이 바뀐다). */
    List<Location> list(Location dir) {
        note(dir.host());
        if (!Files.isDirectory(dir.host())) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir.host())) {
            for (Path entry : entries) {
                names.add(entry.getFileName().toString());
            }
        } catch (IOException e) {
            warn(new ToolkitWarning.Unreadable(new SourceRef(dir.env())));
            return List.of();
        }
        names.sort(String::compareTo);
        List<Location> children = new ArrayList<>();
        for (String name : names) {
            dir.child(name).ifPresent(children::add);
        }
        return List.copyOf(children);
    }

    /**
     * 이 폴더에 들어가도 되는가 — 같은 실제 경로(심볼릭 링크를 푼)에 이미 들어갔으면 false. 실제 경로를 알 수 없으면(없는 폴더 등) true — 목록이 빈
     * 것으로 끝난다.
     */
    boolean enter(Location dir) {
        Path real;
        try {
            real = dir.host().toRealPath();
        } catch (IOException e) {
            return true;
        }
        return entered.add(real);
    }

    /**
     * 파일의 프런트매터. 파일을 지문 입력으로 적는다. 지문 재기 중이거나 일반 파일이 아니면 empty. 형식이 틀리면 {@code Malformed}, 읽지 못하면
     * {@code Unreadable} 경고를 싣고 empty.
     */
    Optional<Frontmatter> frontmatter(Location file) {
        return read(file, Frontmatter::read).flatMap(read -> {
            read.error().ifPresent(error -> malformed(file, error));
            return read.value();
        });
    }

    /** 파일 내용을 {@code parser} 로 읽는다. 파일을 지문 입력으로 적는다. 지문 재기 중이거나 일반 파일이 아니면 empty, 읽지 못하면 {@code Unreadable} 경고를 싣고 empty. */
    <T> Optional<T> read(Location file, Parser<T> parser) {
        note(file.host());
        if (opener.isEmpty() || !Files.isRegularFile(file.host())) {
            return Optional.empty();
        }
        try (InputStream in = opener.get().open(file.host())) {
            return Optional.of(parser.parse(in));
        } catch (IOException e) {
            warn(new ToolkitWarning.Unreadable(new SourceRef(file.env())));
            return Optional.empty();
        }
    }

    /** 그 파일의 형식 오류 경고. */
    void malformed(Location file, FrontmatterError error) {
        warn(new ToolkitWarning.Malformed(new SourceRef(file.env()), error.line()));
    }

    void item(ToolkitItem item) {
        items.add(Objects.requireNonNull(item, "item"));
    }

    /** 모은 항목(가려짐 표시) · 경고 · 지문. */
    ToolkitScan scan(ScopePrecedence precedence) {
        return new ToolkitScan(Shadowing.mark(items, precedence), warnings, Optional.of(stamp()));
    }

    ScanStamp stamp() {
        return new ScanStamp(inputs);
    }

    private void warn(ToolkitWarning warning) {
        if (opener.isPresent()) {
            warnings.add(warning);
        }
    }

    private void note(Path path) {
        inputs.computeIfAbsent(path, Collector::modified);
    }

    private static Optional<Instant> modified(Path path) {
        try {
            return Optional.of(Files.getLastModifiedTime(path).toInstant());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
