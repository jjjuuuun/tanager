package tanager.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 만들 때 토큰으로 칠한 컴포넌트가 테마를 바꾼 뒤 새 토큰 색으로 다시 칠해진다(#185). */
class ThemedTest {
    private LookAndFeel previous;

    @BeforeEach
    void installDark() throws Exception {
        previous = UIManager.getLookAndFeel();
        UIManager.setLookAndFeel(new SoftDarkLaf());
    }

    @AfterEach
    void restore() throws Exception {
        UIManager.setLookAndFeel(previous);
    }

    private static Rgb rgb(Color c) {
        return new Rgb(c.getRed(), c.getGreen(), c.getBlue());
    }

    private static Rgb light(ColorToken token) {
        return SoftTheme.LIGHT.load().tokens().get(token);
    }

    @Test
    @DisplayName("글자색 · 배경색 토큰은 LaF 를 바꾸고 다시 칠하면 새 테마 값이다 — 컴포넌트는 그대로다")
    void recolorAfterSwitch() throws Exception {
        // given
        JPanel root = Themed.background(new JPanel(), ColorToken.SIDEBAR);
        JLabel label = Themed.foreground(new JLabel("경로"), ColorToken.MUTED);
        root.add(label);

        // when
        UIManager.setLookAndFeel(new SoftLightLaf());
        SwingUtilities.updateComponentTreeUI(root);
        Themed.recolor(root);

        // then
        assertThat(root.getComponent(0)).isSameAs(label);
        assertThat(rgb(root.getBackground())).isEqualTo(light(ColorToken.SIDEBAR));
        assertThat(rgb(label.getForeground())).isEqualTo(light(ColorToken.MUTED));
    }

    @Test
    @DisplayName("같은 컴포넌트에 토큰을 다시 주면 마지막 토큰으로 칠한다")
    void lastTokenWins() throws Exception {
        // given
        JLabel label = Themed.foreground(new JLabel(), ColorToken.MUTED);
        Themed.foreground(label, ColorToken.WARN);

        // when
        UIManager.setLookAndFeel(new SoftLightLaf());
        SwingUtilities.updateComponentTreeUI(label);
        Themed.recolor(label);

        // then
        assertThat(rgb(label.getForeground())).isEqualTo(light(ColorToken.WARN));
    }

    @Test
    @DisplayName("선 테두리는 그릴 때 지금 테마의 토큰 색으로 그린다")
    void lineBorderPaintsCurrentToken() throws Exception {
        // given
        Border border = Themed.line(1, 0, 0, 0, ColorToken.BORDER);
        JPanel panel = new JPanel();
        panel.setSize(10, 10);
        UIManager.setLookAndFeel(new SoftLightLaf());
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);

        // when
        Graphics2D g = image.createGraphics();
        try {
            border.paintBorder(panel, g, 0, 0, 10, 10);
        } finally {
            g.dispose();
        }

        // then
        assertThat(border.getBorderInsets(panel).top).isEqualTo(1);
        assertThat(border.getBorderInsets(panel).bottom).isZero();
        assertThat(rgb(new Color(image.getRGB(5, 0)))).isEqualTo(light(ColorToken.BORDER));
    }
}
