package tanager.ui;

import com.formdev.flatlaf.FlatLaf;
import java.awt.Component;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import tanager.application.platform.PlatformSelectionError;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.AppEvents;
import tanager.application.port.in.ApplyThemeUseCase;
import tanager.application.port.in.CheckMissingPathsUseCase;
import tanager.application.port.in.ImportConversationsUseCase;
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.ManageHistoryUseCase;
import tanager.application.port.in.OpenPaneUseCase;
import tanager.application.port.in.QueryHistoryTreeUseCase;
import tanager.application.port.in.RecordWindowStateUseCase;
import tanager.application.port.in.SettingsUseCase;
import tanager.application.port.in.StartSessionUseCase;
import tanager.application.port.in.WatchResourcesUseCase;
import tanager.application.port.out.AppConfig;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.PaneId;
import tanager.domain.workspace.WindowState;
import tanager.domain.workspace.Workspace;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.font.TerminalFonts;
import tanager.ui.i18n.Msg;
import tanager.ui.i18n.NoticeText;
import tanager.ui.motion.Motion;
import tanager.ui.terminal.TerminalSettings;
import tanager.ui.theme.JediTermColors;
import tanager.ui.theme.SoftTheme;

/**
 * Swing 화면의 입구. 조립 루트(app/bootstrap)만 부른다. 소프트 테마(설정 + OS 를 따라 실행 중에도 바뀜, #185) · 번들 D2Coding · 분할한
 * 터미널 창들(ADR-0011 · 0016).
 */
public final class SwingUi {
    private static final System.Logger LOG = System.getLogger(SwingUi.class.getName());

    private SwingUi() {}

    /**
     * EDT 에서 메인 창을 띄우고 {@code initial} 의 창마다 셸을 연다(기억한 셸이 없으면 셸 선택 화면). {@code events} 의 저장소 알림은 상태줄에 보인다.
     *
     * @param panes 창 닫기 · 포커스 · 분할 · 크기 · 줌 · 교환(키보드 · 마우스, #148)
     * @param settings 지금 설정과 설정 파일 — "설정 열기"(Ctrl+,)가 연다(FR-CFG-03). 바뀐 설정은 {@code events} 의 SettingsChanged 로 온다
     * @param resources 상태줄 오른쪽의 리소스 표시(#187 · #188). 창을 띄울 때 구독하고 끝낼 때 해제한다
     * @param missingPaths 창이 활성화되면 사라진 이력 경로를 다시 확인한다(FR-HIS-08, #186)
     * @param conversations 창이 활성화되면 오래된 이전 대화 기록을 다시 읽는다(FR-TK-09, #215)
     * @param appearance 지금 쓸 테마 · 움직임(설정 + OS, #184). 시작 테마로 깔고, 바뀌면 화면 · 모든 터미널에 바로 쓴다(#185). 창이 활성화될 때
     *     OS 값을 다시 읽게 한다. 움직임을 끄면 전환 효과 없이 바로 바뀐다(FR-UI-04)
     * @param initial 처음 그릴 작업 공간 — 시작 때 복원한 탭 · 분할 · 창별 명세 · 포커스(#179). 창마다 명세대로 셸을 띄운다
     * @param windowState 메인 창의 자리 · 크기 · 최대화 · 사이드바를 기록한다(#180). 바뀌면 작업 공간과 같이 1초 안에 저장된다
     * @param initialWindow 시작 때 복원한 창 상태. 저장된 자리가 지금 모니터들에 보이지 않으면 주 모니터의 기본 자리에 띄운다
     * @param onShutdown 앱을 끝낼 때 셸을 닫은 뒤 EDT 밖에서 한 번 부른다(미저장 이력 저장 등, ADR-0011). 끝나면 프로세스를 끝낸다
     */
    public static void showMainWindow(
            StartSessionUseCase sessions,
            ListProfilesUseCase profiles,
            QueryHistoryTreeUseCase historyTree,
            ManageHistoryUseCase historyManager,
            PaneCommands panes,
            OpenPaneUseCase open,
            SettingsUseCase settings,
            AppEvents events,
            WatchResourcesUseCase resources,
            CheckMissingPathsUseCase missingPaths,
            ImportConversationsUseCase conversations,
            ApplyThemeUseCase appearance,
            Workspace initial,
            RecordWindowStateUseCase windowState,
            WindowState initialWindow,
            Runnable onShutdown) {
        // 창을 만들기 전에 구독한다 — 이력 불러오기 알림이 창보다 먼저 올 수 있다. 창은 앱과 수명이 같아 해제하지 않는다
        NoticeInbox inbox = new NoticeInbox();
        events.subscribeOnUi(event -> Notices.messageOf(event).ifPresent(inbox::accept));
        // 셸이 알린 경로를 그 창의 머리줄 · 상태줄로(#82). 콜백과 창 만들기 모두 EDT 에서 돈다 — 창이 생기기 전 보고는 받을 창이 없다
        AtomicReference<MainWindow> shown = new AtomicReference<>();
        // 창이 생기기 전에 온 설정은 들고 있다가 창을 만들 때 넣는다(#183)
        AtomicReference<AppConfig> latestSettings = new AtomicReference<>();
        events.subscribeOnUi(event -> {
            // 창 배치는 StateActor 가 정하고 화면은 따라 그린다(#89). 마지막 창이 닫히면 앱을 끝낸다
            if (event instanceof AppEvent.LayoutChanged(Workspace workspace)) {
                Optional.ofNullable(shown.get()).ifPresent(window -> window.layoutChanged(workspace));
            }
            if (event instanceof AppEvent.LastPaneClosed) {
                Optional.ofNullable(shown.get()).ifPresent(MainWindow::lastPaneClosed);
            }
            // 키 재정의는 다시 시작하지 않고 바로 쓴다(#183)
            if (event instanceof AppEvent.SettingsChanged(AppConfig config)) {
                latestSettings.set(config);
                Optional.ofNullable(shown.get()).ifPresent(window -> window.settingsChanged(config));
            }
            if (event instanceof AppEvent.SessionCwdChanged(PaneId pane, WorkspacePath cwd)) {
                Optional.ofNullable(shown.get()).ifPresent(window -> window.cwdChanged(pane, cwd));
            }
            // 사이드바는 이력 · 세션 · 환경 순서 · 가져온 대화가 바뀌면 다시 투영한다(#84 · #215). 몰리면 한 번만 그린다
            if (event instanceof AppEvent.HistoryChanged
                    || event instanceof AppEvent.SessionsChanged
                    || event instanceof AppEvent.SessionCwdChanged
                    || event instanceof AppEvent.EnvironmentsChanged
                    || event instanceof AppEvent.MissingPathsChanged
                    || event instanceof AppEvent.ConversationsChanged) {
                Optional.ofNullable(shown.get()).ifPresent(MainWindow::historyChanged);
            }
        });
        SwingUtilities.invokeLater(() -> {
            List<String> warnings = new ArrayList<>();
            // 글꼴이 없어도 창은 띄운다 — 논리 글꼴 Monospaced 로 대신하고 상태줄에 알린다
            TerminalFontSet fonts = TerminalFonts.registerBundled().fold(set -> set, error -> {
                warnings.add(Msg.APP_FONT_FALLBACK.text(error));
                return TerminalFontSet.fallback();
            });
            SoftTheme startTheme = SoftTheme.of(appearance.current().theme());
            if (!installTheme(startTheme)) {
                showFatalAndExit(Msg.APP_THEME_FAILED.text());
                return;
            }
            TerminalSettings terminal = new TerminalSettings(fonts, JediTermColors.of(startTheme));
            Motion motion = Motion.fromTheme(appearance.current().animations());
            Executor background =
                    task -> Thread.ofVirtual().name("tanager-session-start").start(task);
            MainWindow window = new MainWindow(
                    sessions,
                    profiles,
                    historyTree,
                    historyManager,
                    panes,
                    open,
                    settings,
                    resources,
                    missingPaths,
                    conversations,
                    onShutdown,
                    terminal,
                    motion,
                    background,
                    initial,
                    windowState,
                    initialWindow);
            shown.set(window);
            Optional.ofNullable(latestSettings.get()).ifPresent(window::settingsChanged);
            window.show(warnings);
            inbox.attach(window::notify);
            // 창을 띄운 뒤 구독한다 — 앱과 수명이 같아 해제하지 않는다. 구독 전에 바뀐 값은 current() 로 한 번 맞춘다
            LiveTheme theme = new LiveTheme(
                    startTheme,
                    terminal,
                    new SnapshotFade(),
                    () -> List.<Component>of(Window.getWindows()),
                    window::themeChanged);
            appearance.subscribe(theme::apply);
            theme.apply(appearance.current());
            window.whenActivated(appearance::refresh);
        });
    }

    /** 플랫폼을 고르지 못해 시작할 수 없음을 알리고 끝낸다. */
    public static void showFatalAndExit(PlatformSelectionError error) {
        showFatalAndExit(NoticeText.DEFAULT.of(error));
    }

    /** 시작할 수 없는 오류를 알리고 끝낸다. */
    private static void showFatalAndExit(String message) {
        SwingUtilities.invokeLater(() -> {
            installTheme(SoftTheme.DARK);
            JOptionPane.showMessageDialog(null, message, Msg.APP_CANNOT_START.text(), JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        });
    }

    // FlatLaf.setup 은 실패하면 예외 대신 false 를 준다
    private static boolean installTheme(SoftTheme theme) {
        boolean installed = FlatLaf.setup(theme.lookAndFeel());
        if (!installed) {
            LOG.log(System.Logger.Level.WARNING, "소프트 테마를 설치하지 못했다: {0}", theme);
        }
        return installed;
    }
}
