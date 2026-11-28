package tanager.application.port.out;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.history.PathTranslator;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.history.WslMount;
import tanager.domain.shared.Result;

/**
 * {@link HostFiles} 의 수작업 Fake(#205). 스레드 안전.
 *
 * <p>{@code hostPath} 는 Windows 와 같은 규칙이다(마운트 루트 /mnt/): Windows 경로는 그대로, 드라이브 아래 WSL 경로는 {@code C:\…}, 그 밖의
 * WSL 경로는 {@code \\wsl.localhost\<배포판>\…}. {@link #withHostPath} 로 경로마다 덮을 수 있다.
 *
 * <p>{@code home} 은 {@link #withHome} · {@link #withHomeError} 로 정한 값이고, 정하지 않은 환경은 {@link HomeError.NotRunnable} 이다.
 * 받은 환경을 {@link #homeRequests()} 에 기록한다 — "VM 을 깨우지 않는다"를 검사할 때 쓴다.
 */
public final class FakeHostFiles implements HostFiles {
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();

    private final Map<ShellEnvironment, Result<WorkspacePath, HomeError>> homes = new HashMap<>();
    private final Map<WorkspacePath, Optional<Path>> hostPaths = new HashMap<>();
    private final List<ShellEnvironment> homeRequests = new ArrayList<>();

    /** {@code env} 의 홈을 {@code home} 으로 답한다. */
    public synchronized FakeHostFiles withHome(ShellEnvironment env, WorkspacePath home) {
        homes.put(Objects.requireNonNull(env, "env"), Result.ok(Objects.requireNonNull(home, "home")));
        return this;
    }

    /** {@code error.env()} 의 홈을 이 오류로 답한다. */
    public synchronized FakeHostFiles withHomeError(HomeError error) {
        homes.put(error.env(), Result.err(error));
        return this;
    }

    /** {@code path} 의 호스트 경로를 {@code host} 로 답한다(empty 면 열 수 없음). */
    public synchronized FakeHostFiles withHostPath(WorkspacePath path, Optional<Path> host) {
        hostPaths.put(Objects.requireNonNull(path, "path"), Objects.requireNonNull(host, "host"));
        return this;
    }

    @Override
    public synchronized Optional<Path> hostPath(WorkspacePath path) {
        Objects.requireNonNull(path, "path");
        Optional<Path> fixed = hostPaths.get(path);
        if (fixed != null) {
            return fixed;
        }
        return switch (path.env()) {
            case ShellEnvironment.Windows _ -> Optional.of(Path.of(path.path()));
            case ShellEnvironment.Wsl(String distro) ->
                PathTranslator.translate(path, WINDOWS, WslMount.DEFAULT)
                        .map(drive -> Path.of(drive.path()))
                        .or(() -> Optional.of(
                                Path.of("\\\\wsl.localhost\\" + distro + "\\" + String.join("\\", path.segments()))));
        };
    }

    @Override
    public synchronized Result<WorkspacePath, HomeError> home(ShellEnvironment env) {
        Objects.requireNonNull(env, "env");
        homeRequests.add(env);
        return homes.getOrDefault(env, Result.err(new HomeError.NotRunnable(env)));
    }

    /** {@code home} 이 받은 환경들(받은 순서, 불변 사본). */
    public synchronized List<ShellEnvironment> homeRequests() {
        return List.copyOf(homeRequests);
    }
}
