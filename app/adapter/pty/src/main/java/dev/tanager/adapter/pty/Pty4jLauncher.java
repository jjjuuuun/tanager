package dev.tanager.adapter.pty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.windows.winpty.WinPtyProcess;
import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.application.port.out.LaunchError;
import dev.tanager.application.port.out.PtyLauncher;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.application.port.out.TerminalStream;
import dev.tanager.domain.shared.Result;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** pty4j 로 PTY 프로세스를 띄운다. Windows 에서는 ConPTY, 그 밖에서는 Unix PTY(ADR-0001 · 0007). */
public final class Pty4jLauncher implements PtyLauncher {
    private static final System.Logger LOG = System.getLogger(Pty4jLauncher.class.getName());

    @Override
    public Result<TerminalStream, LaunchError> launch(LaunchCommand command, TerminalSize initialSize) {
        Optional<LaunchError> invalid = precheck(command);
        if (invalid.isPresent()) {
            return Result.err(invalid.get());
        }
        PtyProcess process;
        try {
            process = new PtyProcessBuilder(command.argv().toArray(String[]::new))
                    .setDirectory(command.workingDirectory().orElse(null))
                    .setEnvironment(environment(command))
                    .setInitialColumns(initialSize.columns())
                    .setInitialRows(initialSize.rows())
                    .setUseWinConPty(true)
                    .start();
        } catch (IOException | LinkageError e) {
            // LinkageError: conpty.dll · JNA 를 읽지 못했다 — 앱 전체가 아니라 이 세션만 실패로 돌린다
            return Result.err(new LaunchError.StartFailed(detail(e)));
        }
        if (process instanceof WinPtyProcess) {
            LOG.log(Level.WARNING, "ConPTY 를 쓰지 못해 winpty 로 시작했다(pid {0}) — 출력 인코딩 · 크기 반영이 다를 수 있다", process.pid());
        }
        return Result.ok(new Pty4jTerminalStream(process));
    }

    // pty4j 는 없는 작업 디렉터리를 만나면 ConPTY 대신 winpty 로 조용히 넘어가거나 모호한 오류를 낸다 — 먼저 확인한다
    private static Optional<LaunchError> precheck(LaunchCommand command) {
        String executable = command.argv().getFirst();
        // 상대 이름(cmd.exe 등)은 PATH 검색 결과라 여기서 판단하지 않는다
        boolean missingExecutable = parse(executable)
                .filter(Path::isAbsolute)
                .filter(path -> !Files.isRegularFile(path))
                .isPresent();
        if (missingExecutable) {
            return Optional.of(new LaunchError.ExecutableNotFound(executable));
        }
        Optional<String> directory = command.workingDirectory();
        if (directory.isPresent()
                && parse(directory.get()).filter(Files::isDirectory).isEmpty()) {
            return Optional.of(new LaunchError.WorkingDirectoryNotFound(directory.get()));
        }
        return Optional.empty();
    }

    private static Optional<Path> parse(String path) {
        try {
            return Optional.of(Path.of(path));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static Map<String, String> environment(LaunchCommand command) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.putAll(command.environment());
        return env;
    }

    private static String detail(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getName() : message;
    }
}
