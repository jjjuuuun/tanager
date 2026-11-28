package tanager.adapter.toolkit.claude;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;

/**
 * 테스트용 가짜 홈 · 프로젝트. {@code src/test/resources/claude/<이름>/} 을 임시 폴더로 복사하며 {@code dot-claude} 를 {@code .claude}
 * 로 바꾼다 — 저장소 안에 진짜 {@code .claude/} 를 두면 이 저장소에서 도는 Claude Code 가 픽스처 스킬을 읽는다. 내용은 모두 합성이다.
 *
 * <p>환경 경로 → 앱이 읽을 경로 변환({@link #host})은 가장 긴 접두 경로를 찾아 나머지 이름을 임시 폴더 아래로 잇는다({@code
 * \\wsl.localhost} 변환과 같은 모양).
 */
final class ClaudeFixture {

    private record Mount(WorkspacePath env, Path host) {}

    private final List<Mount> mounts = new ArrayList<>();

    /** {@code resource} 픽스처를 {@code target} 으로 복사하고 환경 경로 {@code env} 에 붙인다. */
    ClaudeFixture copy(String resource, Path target, WorkspacePath env) {
        copyTree(resourceDir(resource), target);
        return mount(env, target);
    }

    /** 복사 없이 환경 경로 {@code env} 를 {@code host} 에 붙인다. */
    ClaudeFixture mount(WorkspacePath env, Path host) {
        mounts.add(new Mount(env, host));
        return this;
    }

    Optional<Path> host(WorkspacePath path) {
        Optional<Mount> best = Optional.empty();
        for (Mount mount : mounts) {
            boolean under = mount.env().env().equals(path.env())
                    && startsWith(path.segments(), mount.env().segments())
                    && mount.env().root().equals(path.root());
            if (under
                    && best.map(b -> b.env().segments().size()
                                    < mount.env().segments().size())
                            .orElse(true)) {
                best = Optional.of(mount);
            }
        }
        return best.map(mount -> {
            Path out = mount.host();
            for (String name : path.segments()
                    .subList(mount.env().segments().size(), path.segments().size())) {
                out = out.resolve(name);
            }
            return out;
        });
    }

    Function<WorkspacePath, Optional<Path>> hostFunction() {
        return this::host;
    }

    static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static boolean startsWith(List<String> segments, List<String> prefix) {
        return segments.size() >= prefix.size()
                && segments.subList(0, prefix.size()).equals(prefix);
    }

    private static Path resourceDir(String resource) {
        URL url = ClaudeFixture.class.getResource("/claude/" + resource);
        if (url == null) {
            throw new IllegalStateException("픽스처가 없다: " + resource);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copyTree(Path from, Path to) {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.sorted(Comparator.naturalOrder()).toList()) {
                Path target = to;
                for (Path name : from.relativize(source)) {
                    String text = name.toString();
                    target = target.resolve(text.equals("dot-claude") ? ".claude" : text);
                }
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
