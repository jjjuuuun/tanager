package tanager.bootstrap;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.ServiceLoader;
import tanager.adapter.pty.Pty4jLauncher;
import tanager.application.platform.OperatingSystem;
import tanager.application.platform.PlatformSelection;
import tanager.application.platform.PlatformSelectionError;
import tanager.application.port.in.AppEvents;
import tanager.application.port.out.PlatformModule;
import tanager.application.state.UiAppEvents;
import tanager.domain.shared.Result;
import tanager.ui.SwingUi;
import tanager.ui.SwingUiDispatcher;

/**
 * 조립 루트(ADR-0002). OS 를 읽고 플랫폼 모듈을 고르는 곳은 여기 한 곳뿐이다(architecture.md).
 */
public final class TanagerApp {
    private static final System.Logger LOG = System.getLogger(TanagerApp.class.getName());

    private TanagerApp() {}

    public static void main(String[] args) {
        OperatingSystem os = currentOs();
        List<PlatformModule> candidates = ServiceLoader.load(PlatformModule.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        var selection = PlatformSelection.select(candidates, os);

        if (GraphicsEnvironment.isHeadless()) {
            LOG.log(
                    System.Logger.Level.INFO,
                    "화면이 없는 환경 — 플랫폼 선택 결과만 남기고 끝낸다: {0}",
                    selection.fold(PlatformModule::id, e -> e.describe()));
            return;
        }
        switch (selection) {
            case Result.Ok<PlatformModule, PlatformSelectionError>(PlatformModule platform) -> {
                LOG.log(System.Logger.Level.INFO, "플랫폼: {0} ({1})", platform.id(), os.describe());
                Assembly assembly = Assembly.assemble(platform, new Pty4jLauncher());
                AppEvents events = new UiAppEvents(assembly.state(), new SwingUiDispatcher());
                SwingUi.showMainWindow(
                        assembly.sessions(),
                        assembly.profiles(),
                        assembly.historyTree(),
                        assembly.historyManager(),
                        assembly.panes(),
                        assembly.panes(),
                        assembly.panes(),
                        events,
                        assembly::shutdown);
            }
            case Result.Err<PlatformModule, PlatformSelectionError>(PlatformSelectionError error) -> {
                LOG.log(System.Logger.Level.ERROR, error.describe());
                SwingUi.showFatalAndExit(error);
            }
        }
    }

    /** 시스템에서 OS 정보를 읽는 유일한 곳. */
    static OperatingSystem currentOs() {
        return new OperatingSystem(
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown"));
    }
}
