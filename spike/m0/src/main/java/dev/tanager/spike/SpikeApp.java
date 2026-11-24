package dev.tanager.spike;

import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.pty4j.PtyProcess;
import java.awt.BorderLayout;
import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * M0 스파이크: 왼쪽 WSL bash, 오른쪽 위 Windows PowerShell 5.1, 오른쪽 아래 cmd.
 * 각 창 머리줄에 OSC 로 가로챈 cwd 를 보여 준다. 한글 IME 확인(항목 b)은 사람이 이 창에서 한다.
 * 인자 --selftest 면 화면 없이 자동 확인만 하고 끝낸다.
 */
public final class SpikeApp {
    private SpikeApp() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--selftest")) {
            System.exit(SelfTest.run() == 0 ? 0 : 1);
        }
        Shells.installScripts();
        SwingUtilities.invokeLater(SpikeApp::show);
    }

    private static void show() {
        JFrame f = new JFrame("Tanager M0 스파이크 — 한글을 입력해 보세요");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pane(Shells.Kind.POWERSHELL), pane(Shells.Kind.CMD));
        right.setResizeWeight(0.5);
        JSplitPane root = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, pane(Shells.Kind.WSL_BASH), right);
        root.setResizeWeight(0.5);
        f.add(root);
        f.setSize(1400, 850);
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    private static JPanel pane(Shells.Kind kind) {
        JLabel head = new JLabel(kind + " — 시작 중…");
        head.setBorder(new EmptyBorder(4, 8, 4, 8));
        // 왼쪽(WSL): Consolas + 글자 단위 대체 글꼴(Malgun Gothic) — 제품에서 쓸 방식 후보
        // 오른쪽(PowerShell · cmd): 한글이 들어 있는 고정폭 글꼴(D2Coding)을 바로 사용
        boolean fallbackMode = kind == Shells.Kind.WSL_BASH;
        Font primary = fallbackMode ? new Font("Consolas", Font.PLAIN, 15) : hangulMono(15);
        DefaultSettingsProvider settings = new DefaultSettingsProvider() {
            @Override
            public Font getTerminalFont() { return primary; }

            @Override
            public float getTerminalFontSize() { return 15; }
        };
        JediTermWidget term = fallbackMode ? new FallbackFontWidget(settings) : new JediTermWidget(100, 30, settings);
        String fontNote = fallbackMode ? "글꼴 Consolas + 대체 Malgun Gothic" : "글꼴 " + primary.getFamily();
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(head, BorderLayout.NORTH);
        panel.add(term, BorderLayout.CENTER);
        try {
            PtyProcess p = Shells.start(kind, 100, 30);
            String impl = p.getClass().getSimpleName();
            SpikeConnector c = new SpikeConnector(kind.name(), p,
                    cwd -> SwingUtilities.invokeLater(() -> head.setText(kind + " [" + impl + "] " + fontNote + " · cwd = " + cwd)),
                    SpikeApp::log);
            head.setText(kind + " [" + impl + "] " + fontNote + " — 첫 프롬프트 대기");
            term.setTtyConnector(c);
            term.start();
        } catch (Exception e) {
            head.setText(kind + " 기동 실패: " + e);
        }
        return panel;
    }

    /** 설치된 글꼴 중 한글을 그릴 수 있는 고정폭 글꼴. 없으면 Java 논리 글꼴 Monospaced(한글 대체 포함). */
    private static Font hangulMono(int size) {
        String[] families = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (String want : new String[] {"D2Coding", "NanumGothicCoding", "Nanum Gothic Coding"}) {
            for (String f : families) {
                Font font = new Font(f, Font.PLAIN, size);
                if (f.startsWith(want) && font.canDisplay('한')) return font;
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    private static void log(String s) {
        try {
            Files.createDirectories(Shells.WORK);
            Files.writeString(Shells.WORK.resolve("ui.log"), LocalTime.now() + " " + s + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 스파이크 로그 실패는 무시
        }
    }
}
