package dev.tanager.ui;

import com.formdev.flatlaf.FlatLaf;
import dev.tanager.application.port.in.ListProfilesUseCase;
import dev.tanager.application.port.in.StartSessionUseCase;
import dev.tanager.ui.font.TerminalFontSet;
import dev.tanager.ui.font.TerminalFonts;
import dev.tanager.ui.terminal.TerminalSettings;
import dev.tanager.ui.theme.JediTermColors;
import dev.tanager.ui.theme.SoftDarkLaf;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Swing 화면의 입구. 조립 루트(app/bootstrap)만 부른다. M2: 소프트 다크 테마 · 번들 D2Coding · 터미널 창 하나(ADR-0011).
 */
public final class SwingUi {
    private static final System.Logger LOG = System.getLogger(SwingUi.class.getName());

    private SwingUi() {}

    /** EDT 에서 메인 창을 띄우고 기억한 셸 하나를 연다(없으면 셸 선택 화면). */
    public static void showMainWindow(StartSessionUseCase sessions, ListProfilesUseCase profiles) {
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
            new MainWindow(sessions, profiles, settings, background).show(warnings);
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
