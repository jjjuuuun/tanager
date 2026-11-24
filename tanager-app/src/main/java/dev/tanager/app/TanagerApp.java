package dev.tanager.app;

import java.awt.GraphicsEnvironment;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** 조립 루트. M1 에서는 빈 창만 띄운다(플랫폼 선택 · 테마 · 터미널은 후속 PR). */
public final class TanagerApp {
    private static final System.Logger LOG = System.getLogger(TanagerApp.class.getName());

    private TanagerApp() {}

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            LOG.log(System.Logger.Level.INFO, "화면이 없는 환경 — 창을 띄우지 않고 끝낸다");
            return;
        }
        SwingUtilities.invokeLater(TanagerApp::showWindow);
    }

    private static void showWindow() {
        JFrame frame = new JFrame("Tanager");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1200, 800);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
