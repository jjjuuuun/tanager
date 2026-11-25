package dev.tanager.ui;

import com.formdev.flatlaf.FlatLightLaf;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Swing 화면의 입구. 조립 루트(app/bootstrap)만 부른다.
 * M1: FlatLaf 기본 테마의 빈 창. 소프트 테마 · 터미널 · 사이드바는 M2 부터.
 */
public final class SwingUi {
    private SwingUi() {}

    /** EDT 에서 메인 창을 띄운다. */
    public static void showMainWindow(String platformId) {
        SwingUtilities.invokeLater(() -> {
            FlatLightLaf.setup();
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
            FlatLightLaf.setup();
            JOptionPane.showMessageDialog(null, message, "Tanager 를 시작할 수 없습니다", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        });
    }
}
