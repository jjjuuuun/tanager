package tanager.ui;

import com.formdev.flatlaf.FlatLaf;
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
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.ManageHistoryUseCase;
import tanager.application.port.in.OpenPaneUseCase;
import tanager.application.port.in.QueryHistoryTreeUseCase;
import tanager.application.port.in.SettingsUseCase;
import tanager.application.port.in.StartSessionUseCase;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.SystemAppearance;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.PaneId;
import tanager.domain.workspace.Workspace;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.font.TerminalFonts;
import tanager.ui.i18n.Msg;
import tanager.ui.i18n.NoticeText;
import tanager.ui.motion.Motion;
import tanager.ui.terminal.TerminalSettings;
import tanager.ui.theme.JediTermColors;
import tanager.ui.theme.SoftDarkLaf;

/**
 * Swing 화면의 입구. 조립 루트(app/bootstrap)만 부른다. M2: 소프트 다크 테마 · 번들 D2Coding · 터미널 창 하나(ADR-0011).
 */
public final class SwingUi {
    private static final System.Logger LOG = System.getLogger(SwingUi.class.getName());

    private SwingUi() {}

    /**
     * EDT 에서 메인 창을 띄우고 기억한 셸 하나를 연다(없으면 셸 선택 화면). {@code events} 의 저장소 알림은 상태줄에 보인다.
     *
     * @param panes 창 닫기 · 포커스 · 분할 · 크기 · 줌 · 교환(키보드 · 마우스, #148)
     * @param settings 지금 설정과 설정 파일 — "설정 열기"(Ctrl+,)가 연다(FR-CFG-03). 바뀐 설정은 {@code events} 의 SettingsChanged 로 온다
     * @param appearance OS 외관 설정. 애니메이션을 껐으면 전환 효과 없이 바로 바뀐다(FR-UI-04)
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
            SystemAppearance appearance,
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
            // 사이드바는 이력 · 세션 · 환경 순서가 바뀌면 다시 투영한다(#84). 몰리면 한 번만 그린다
            if (event instanceof AppEvent.HistoryChanged
                    || event instanceof AppEvent.SessionsChanged
                    || event instanceof AppEvent.SessionCwdChanged
                    || event instanceof AppEvent.EnvironmentsChanged) {
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
            if (!installTheme()) {
                showFatalAndExit(Msg.APP_THEME_FAILED.text());
                return;
            }
            TerminalSettings terminal = new TerminalSettings(fonts, JediTermColors.current());
            Motion motion = Motion.fromTheme(appearance.animations());
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
                    onShutdown,
                    terminal,
                    motion,
                    background);
            shown.set(window);
            Optional.ofNullable(latestSettings.get()).ifPresent(window::settingsChanged);
            window.show(warnings);
            inbox.attach(window::notify);
        });
    }

    /** 플랫폼을 고르지 못해 시작할 수 없음을 알리고 끝낸다. */
    public static void showFatalAndExit(PlatformSelectionError error) {
        showFatalAndExit(NoticeText.DEFAULT.of(error));
    }

    /** 시작할 수 없는 오류를 알리고 끝낸다. */
    private static void showFatalAndExit(String message) {
        SwingUtilities.invokeLater(() -> {
            installTheme();
            JOptionPane.showMessageDialog(null, message, Msg.APP_CANNOT_START.text(), JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        });
    }

    // FlatLaf.setup 은 실패하면 예외 대신 false 를 준다
    private static boolean installTheme() {
        boolean installed = FlatLaf.setup(new SoftDarkLaf());
        if (!installed) {
            LOG.log(System.Logger.Level.WARNING, "소프트 다크 테마를 설치하지 못했다");
        }
        return installed;
    }
}
