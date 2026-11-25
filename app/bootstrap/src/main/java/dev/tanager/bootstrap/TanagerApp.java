package dev.tanager.bootstrap;

import dev.tanager.application.platform.OperatingSystem;
import dev.tanager.application.platform.PlatformSelection;
import dev.tanager.application.platform.PlatformSelectionError;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.domain.shared.Result;
import dev.tanager.ui.SwingUi;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.ServiceLoader;

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
                SwingUi.showMainWindow(platform.id());
            }
            case Result.Err<PlatformModule, PlatformSelectionError>(PlatformSelectionError error) -> {
                LOG.log(System.Logger.Level.ERROR, error.describe());
                SwingUi.showFatalAndExit(error.describe());
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
