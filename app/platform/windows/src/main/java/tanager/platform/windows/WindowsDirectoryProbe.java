package tanager.platform.windows;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tanager.application.port.out.DirectoryProbe;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.history.WslMount;

/**
 * 경로를 Windows 쪽 파일 경로로 바꿔 확인한다(#53). WSL 의 드라이브 마운트 아래는 {@code <D>:\…}, 그 밖의 WSL 경로는 {@code
 * \\wsl.localhost\<배포판>\…}, Windows 경로는 그대로.
 *
 * <p>확인은 {@code executor} 에서 하고 {@code timeout} 까지만 기다린다 — 깨어나지 않는 VM 이나 끊긴 네트워크 드라이브는 끝없이 막힐 수
 * 있다. 시간을 넘긴 확인은 버리고(스레드는 OS 가 답할 때 끝난다) 모름을 돌려준다.
 */
final class WindowsDirectoryProbe implements DirectoryProbe {
    private static final Logger LOG = System.getLogger(WindowsDirectoryProbe.class.getName());

    private final WindowsFiles files;
    private final WslMountRoots mounts;
    private final Executor executor;

    WindowsDirectoryProbe(WindowsFiles files, WslMountRoots mounts, Executor executor) {
        this.files = Objects.requireNonNull(files, "files");
        this.mounts = Objects.requireNonNull(mounts, "mounts");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public Optional<Boolean> isDirectory(WorkspacePath path, Duration timeout) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(timeout, "timeout");
        CompletableFuture<Optional<Boolean>> answer = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    probe(path, answer);
                } catch (RuntimeException e) {
                    answer.completeExceptionally(e);
                }
            });
            return answer.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException | TimeoutException e) {
            return Optional.empty();
        } catch (ExecutionException e) {
            LOG.log(Level.WARNING, "경로 확인이 예외로 끝남 — 모름으로 처리: {0}", e.getCause().toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private void probe(WorkspacePath path, CompletableFuture<Optional<Boolean>> answer) {
        switch (path.env()) {
            case ShellEnvironment.Windows _ -> answer.complete(files.isDirectory(path.path()));
            case ShellEnvironment.Wsl(String distro) -> {
                Optional<String> onDrive = WindowsHostPaths.onDrive(path, mounts.snapshot());
                if (onDrive.isPresent()) {
                    answer.complete(files.isDirectory(onDrive.get()));
                    return;
                }
                Optional<Boolean> found = files.isDirectory(WindowsHostPaths.wslUnc(distro, path));
                answer.complete(found);
                // 배포판이 답했으면 이미 깨어 있다 — 이때만 wsl.conf 를 읽어 다음 확인 · 경로 변환이 그 루트를 쓰게 한다
                if (found.isPresent() && !mounts.knows(distro)) {
                    learnMountRoot(distro);
                }
            }
        }
    }

    private void learnMountRoot(String distro) {
        files.readText(WindowsHostPaths.wslRoot(distro) + "\\etc\\wsl.conf")
                .ifPresent(conf -> mounts.remember(
                        distro, WslConfParser.automountRoot(conf).orElse(WslMount.DEFAULT)));
    }
}
