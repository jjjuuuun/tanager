package dev.tanager.ui;

import com.formdev.flatlaf.FlatLaf;
import dev.tanager.ui.font.TerminalFonts;
import dev.tanager.ui.theme.SoftDarkLaf;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Swing 화면의 입구. 조립 루트(app/bootstrap)만 부른다.
 * M2: 소프트 다크 테마 + 번들 D2Coding 등록. 터미널 · 사이드바는 이후 이슈.
 */
public final class SwingUi {
    private static final System.Logger LOG = System.getLogger(SwingUi.class.getName());

    private SwingUi() {}

    /** EDT 에서 메인 창을 띄운다. */
    public static void showMainWindow(String platformId) {
        SwingUtilities.invokeLater(() -> {
            // 글꼴은 LaF 보다 먼저 등록한다. 실패해도 창은 띄운다(터미널은 Monospaced 로 대체)
            TerminalFonts.registerBundled()
                    .error()
                    .ifPresent(e -> LOG.log(System.Logger.Level.WARNING, "번들 글꼴 등록 실패: {0}", e));
            installTheme();
            JFrame frame = new JFrame("Tanager — " + platformId);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(1200, 800);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
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

    private static void installTheme() {
        // FlatLaf.setup 은 실패하면 예외 대신 false 를 준다. 기본 LaF 로 계속한다
        if (!FlatLaf.setup(new SoftDarkLaf())) {
            LOG.log(System.Logger.Level.WARNING, "소프트 다크 테마를 설치하지 못했다 — 기본 테마로 계속");
        }
    }
}
