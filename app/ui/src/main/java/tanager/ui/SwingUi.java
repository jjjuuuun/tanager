package tanager.ui;

import com.formdev.flatlaf.FlatLaf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.AppEvents;
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.StartSessionUseCase;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.PaneId;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.font.TerminalFonts;
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
     * @param onShutdown 앱을 끝낼 때 셸을 닫은 뒤 EDT 밖에서 한 번 부른다(미저장 이력 저장 등, ADR-0011). 끝나면 프로세스를 끝낸다
     */
    public static void showMainWindow(
            StartSessionUseCase sessions, ListProfilesUseCase profiles, AppEvents events, Runnable onShutdown) {
        // 창을 만들기 전에 구독한다 — 이력 불러오기 알림이 창보다 먼저 올 수 있다. 창은 앱과 수명이 같아 해제하지 않는다
        NoticeInbox inbox = new NoticeInbox();
        events.subscribeOnUi(event -> Notices.messageOf(event).ifPresent(inbox::accept));
        // 셸이 알린 경로를 그 창의 머리줄 · 상태줄로(#82). 콜백과 창 만들기 모두 EDT 에서 돈다 — 창이 생기기 전 보고는 받을 창이 없다
        AtomicReference<MainWindow> shown = new AtomicReference<>();
        events.subscribeOnUi(event -> {
            if (event instanceof AppEvent.SessionCwdChanged(PaneId pane, WorkspacePath cwd)) {
                Optional.ofNullable(shown.get()).ifPresent(window -> window.cwdChanged(pane, cwd));
            }
        });
        SwingUtilities.invokeLater(() -> {
            List<String> warnings = new ArrayList<>();
            // 글꼴이 없어도 창은 띄운다 — 논리 글꼴 Monospaced 로 대신하고 상태줄에 알린다
            TerminalFontSet fonts = TerminalFonts.registerBundled().fold(set -> set, error -> {
                warnings.add("번들 글꼴을 쓸 수 없어 Monospaced 로 표시한다 — " + error);
                return TerminalFontSet.fallback();
            });
            if (!installTheme()) {
                showFatalAndExit("소프트 다크 테마를 설치하지 못했다");
                return;
            }
            TerminalSettings settings = new TerminalSettings(fonts, JediTermColors.current());
            Executor background =
                    task -> Thread.ofVirtual().name("tanager-session-start").start(task);
            MainWindow window = new MainWindow(sessions, profiles, onShutdown, settings, background);
            shown.set(window);
            window.show(warnings);
            inbox.attach(window::notify);
        });
    }

    /** 시작할 수 없는 오류를 알리고 끝낸다. */
    public static void showFatalAndExit(String message) {
        SwingUtilities.invokeLater(() -> {
            installTheme();
            JOptionPane.showMessageDialog(null, message, "Tanager 를 시작할 수 없습니다", JOptionPane.ERROR_MESSAGE);
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
