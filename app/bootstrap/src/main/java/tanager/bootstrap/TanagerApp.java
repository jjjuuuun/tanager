package tanager.bootstrap;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import tanager.adapter.pty.Pty4jLauncher;
import tanager.application.platform.OperatingSystem;
import tanager.application.platform.PlatformSelection;
import tanager.application.platform.PlatformSelectionError;
import tanager.application.port.in.AppEvents;
import tanager.application.port.out.PlatformModule;
import tanager.application.port.out.PtyLauncher;
import tanager.application.state.UiAppEvents;
import tanager.domain.shared.Result;
import tanager.ui.PaneCommands;
import tanager.ui.SwingUi;
import tanager.ui.SwingUiDispatcher;

/**
 * 조립 루트(ADR-0002). OS 를 읽고 플랫폼 모듈을 고르는 곳은 여기 한 곳뿐이다(architecture.md).
 */
public final class TanagerApp {
    private static final System.Logger LOG = System.getLogger(TanagerApp.class.getName());
    // 숨은 인수: 첫 창 · 첫 셸 첫 바이트까지 간 뒤 스스로 끝낸다 — AOT 캐시를 만드는 훈련 실행(#221)
    static final String STARTUP_TRAINING = "--startup-training";
    // 빌드가 AOT 캐시를 만들었을 때만 실행 옵션에 넣는 jar 기록 파일 경로(#221)
    private static final String AOT_STAMPS = "tanager.aot.stamps";

    private TanagerApp() {}

    public static void main(String[] args) {
        StartupClock startup = StartupClock.sinceProcessStart();
        startup.mark(StartupPhase.MAIN);
        boolean training = List.of(args).contains(STARTUP_TRAINING);
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
                SwingUiDispatcher ui = new SwingUiDispatcher();
                PtyLauncher shells =
                        new FirstByteLauncher(new Pty4jLauncher(), () -> startup.mark(StartupPhase.FIRST_BYTE));
                Assembly assembly = Assembly.assemble(platform, shells, ui, startup::mark);
                AppEvents events = new UiAppEvents(assembly.state(), ui);
                // 외부 명령(Windows: reg.exe)을 쓸 수 있어 EDT 가 아닌 여기서 한 번 읽는다. 그 뒤의 변경은 ApplyThemeUseCase 가 알린다
                assembly.readAppearance();
                StartupWindow window = StartupWindow.watch(startup);
                startup.reported().thenAccept(line -> LOG.log(System.Logger.Level.INFO, line));
                // 시작을 재고 난 뒤에 한다 — 설치가 밀어 놓은 jar 시각을 되돌려 다음 실행부터 AOT 캐시가 쓰이게
                Optional.ofNullable(System.getProperty(AOT_STAMPS))
                        .map(Path::of)
                        .ifPresent(stamps -> startup.reported().thenRun(() -> restoreStamps(stamps)));
                if (training) {
                    LOG.log(System.Logger.Level.INFO, "시작 훈련 실행 — 첫 창 · 첫 셸 출력 뒤 끝낸다");
                    startup.reported().thenRun(window::close);
                }
                SwingUi.showMainWindow(
                        assembly.sessions(),
                        assembly.profiles(),
                        assembly.historyTree(),
                        assembly.historyManager(),
                        PaneCommands.of(assembly.panes()),
                        assembly.panes(),
                        assembly.settings(),
                        events,
                        assembly.resources(),
                        assembly.missingPaths(),
                        assembly.conversations(),
                        assembly.appearance(),
                        // state.json 은 조립 때 이미 읽었다 — 처음 그리는 배치가 복원한 작업 공간이다(#179)
                        assembly.state().snapshot().workspace(),
                        // 창 상태도 조립 때 읽었다 — 화면 밖인지는 UI 가 지금 모니터들로 판정한다(#180)
                        assembly.windowState(),
                        assembly.state().snapshot().window(),
                        assembly::shutdown);
                // UI 가 구독한 뒤라 불러오기 알림 · 시작 때의 SettingsChanged 를 UI 가 받는다
                assembly.startStore();
                assembly.startSettings();
            }
            case Result.Err<PlatformModule, PlatformSelectionError>(PlatformSelectionError error) -> {
                LOG.log(System.Logger.Level.ERROR, error.describe());
                SwingUi.showFatalAndExit(error);
            }
        }
    }

    private static void restoreStamps(Path stamps) {
        Thread.ofVirtual().name("tanager-aot-stamps").start(() -> {
            int restored = ClasspathStamps.restore(stamps);
            if (restored > 0) {
                LOG.log(System.Logger.Level.INFO, "다음 실행부터 AOT 캐시를 쓰게 jar 수정 시각 {0}개를 되돌렸다", restored);
            }
        });
    }

    /** 시스템에서 OS 정보를 읽는 유일한 곳. */
    static OperatingSystem currentOs() {
        return new OperatingSystem(
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown"));
    }
}
