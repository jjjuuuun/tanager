package tanager.platform.windows;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import tanager.application.port.out.Integration;
import tanager.application.port.out.ShellIntegration;

/**
 * 클래스패스의 셸 통합 스크립트를 {@code <appData>\shell-integration\} 에 푼다(ADR-0014).
 *
 * <p>버전 파일 대신 바이트를 비교한다 — 같으면 건드리지 않고(수정 시각 유지), 다르면 사용자가 고친 파일이라도 되돌린다. 셸이 반쯤 쓴
 * 파일을 읽지 않도록 같은 디렉터리의 {@code <이름>.tmp} 에 쓴 뒤 원자적으로 옮긴다. 이름을 고정해 두므로 이전 실행이 남긴 임시 파일은
 * 다음 설치가 덮어쓴다.
 */
final class WindowsShellIntegration implements ShellIntegration {
    private static final System.Logger LOG = System.getLogger(WindowsShellIntegration.class.getName());
    private static final String DIRECTORY = "shell-integration";
    private static final String RESOURCE_ROOT = "/shell-integration/";
    private static final List<String> SCRIPTS = List.of("tanager.bash", "tanager.ps1");
    private static final String LEFTOVER_TEMPS = "tanager.*.tmp";
    // Windows 는 대상 파일을 다른 프로세스(셸 · 백신)가 열고 있으면 교체를 잠깐 거부한다 — 짧게만 기다린다
    private static final int RETRIES = 3;
    private static final Duration RETRY_DELAY = Duration.ofMillis(100);

    /** 임시 파일을 대상 자리로 원자적으로 옮긴다. 테스트가 실패를 흉내 내는 경계. */
    @FunctionalInterface
    interface Mover {
        void move(Path source, Path target) throws IOException;
    }

    /** 재시도 사이의 대기. 테스트가 실제로 기다리지 않게 하는 경계. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Path appData;
    private final Mover mover;
    private final Sleeper sleeper;

    WindowsShellIntegration(Path appData) {
        this(appData, WindowsShellIntegration::atomicMove, Thread::sleep);
    }

    WindowsShellIntegration(Path appData, Mover mover, Sleeper sleeper) {
        this.appData = Objects.requireNonNull(appData, "appData");
        this.mover = Objects.requireNonNull(mover, "mover");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public Integration install() {
        Path dir = appData.resolve(DIRECTORY);
        Path current = dir;
        try {
            Files.createDirectories(dir);
            removeLeftoverTemps(dir);
            for (String name : SCRIPTS) {
                current = dir.resolve(name);
                installScript(current, resource(name));
            }
            return new Integration.Installed(dir);
        } catch (IOException | RuntimeException e) {
            // 셸은 통합 없이도 떠야 한다 — 예외 대신 이유를 돌려주고, 원인 전체는 로그에 남긴다
            LOG.log(System.Logger.Level.WARNING, "셸 통합 스크립트 설치 실패: " + current, e);
            return new Integration.Unavailable(
                    new Integration.Problem.InstallFailed(String.valueOf(current), cause(e), detail(e)));
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = WindowsShellIntegration.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (in == null) {
                throw new IOException("클래스패스에 " + RESOURCE_ROOT + name + " 이 없다");
            }
            return in.readAllBytes();
        }
    }

    /** 이전 판이 임의 이름으로 남긴 임시 파일까지 지운다. 지우지 못해도 설치는 계속한다 — 고정 이름 tmp 는 어차피 덮어쓴다. */
    private static void removeLeftoverTemps(Path dir) {
        try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(dir, LEFTOVER_TEMPS)) {
            for (Path leftover : leftovers) {
                try {
                    Files.deleteIfExists(leftover);
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.DEBUG, "남은 임시 파일을 지우지 못했다: " + leftover, e);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "남은 임시 파일을 찾지 못했다: " + dir, e);
        }
    }

    private void installScript(Path target, byte[] content) throws IOException {
        if (hasContent(target, content)) {
            return;
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            replace(tmp, target, content);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "임시 파일을 지우지 못했다: " + tmp, e);
            }
        }
    }

    private void replace(Path tmp, Path target, byte[] content) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.write(tmp, content);
                mover.move(tmp, target);
                return;
            } catch (IOException e) {
                // 다른 Tanager 인스턴스가 먼저 같은 내용을 썼다면 목표는 이미 이뤘다
                if (hasContent(target, content)) {
                    return;
                }
                if (attempt == RETRIES) {
                    throw e;
                }
            }
            pause();
        }
    }

    private void pause() throws InterruptedIOException {
        try {
            sleeper.sleep(RETRY_DELAY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("셸 통합 설치 재시도 중 중단됐다");
        }
    }

    private static boolean hasContent(Path target, byte[] content) {
        try {
            return Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), content);
        } catch (IOException e) {
            return false;
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 알려진 파일 문제. 문장은 UI 가 만든다(#137). */
    private static Integration.FileProblem cause(Exception e) {
        return switch (e) {
            case AccessDeniedException _ -> Integration.FileProblem.ACCESS_DENIED;
            case FileAlreadyExistsException _ -> Integration.FileProblem.FILE_EXISTS;
            case DirectoryNotEmptyException _ -> Integration.FileProblem.DIRECTORY_EXISTS;
            case NoSuchFileException _ -> Integration.FileProblem.NO_SUCH_PATH;
            case NotDirectoryException _ -> Integration.FileProblem.NOT_DIRECTORY;
            default -> Integration.FileProblem.OTHER;
        };
    }

    /** 알려지지 않은 문제의 OS 이유. 예외 클래스 이름(FQN)을 그대로 보이지 않는다. 알려진 문제면 비어 있다. */
    private static String detail(Exception e) {
        return switch (e) {
            case AccessDeniedException _,
                    FileAlreadyExistsException _,
                    DirectoryNotEmptyException _,
                    NoSuchFileException _,
                    NotDirectoryException _ -> "";
            case FileSystemException fse when fse.getReason() != null -> fse.getReason();
            case FileSystemException _ -> e.getClass().getSimpleName();
            default ->
                e.getMessage() == null || e.getMessage().isBlank()
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
        };
    }
}
