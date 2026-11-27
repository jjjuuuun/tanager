package tanager.platform.windows;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import tanager.application.port.out.MonitorItem;
import tanager.application.port.out.SystemTools;

/**
 * 상태줄 항목을 누르면 작업 관리자 · 디스크 정리를 연다(FR-MON-04). 작업 관리자는 자동 권한 상승 프로그램이라 {@code CreateProcess}
 * ({@link ProcessBuilder})로 바로 띄우면 오류 740 으로 실패한다 — {@code cmd /c start} 가 ShellExecute 로 띄운다. {@code start} 의 첫
 * 따옴표 인자는 창 제목이라, 공백을 넣어 ProcessBuilder 가 따옴표로 감싸게 한다.
 */
final class WindowsSystemTools implements SystemTools {
    static final String START_TITLE = "Tanager tool";
    private static final System.Logger LOG = System.getLogger(WindowsSystemTools.class.getName());

    private final Consumer<List<String>> launcher;

    WindowsSystemTools(Consumer<List<String>> launcher) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    /** 실제 프로세스로 띄운다. 끝나기를 기다리지 않는다. */
    static WindowsSystemTools system() {
        return new WindowsSystemTools(WindowsSystemTools::start);
    }

    @Override
    public void open(MonitorItem item) {
        String program = switch (item) {
            case CPU, MEMORY, WSL -> "taskmgr.exe";
            case DISK -> "cleanmgr.exe";
        };
        launcher.accept(List.of("cmd.exe", "/c", "start", START_TITLE, program));
    }

    private static void start(List<String> command) {
        try {
            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .getOutputStream()
                    .close();
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "도구를 띄우지 못했다: " + command.getLast(), e);
        }
    }
}
