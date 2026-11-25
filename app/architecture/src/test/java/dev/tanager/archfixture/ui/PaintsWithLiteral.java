package dev.tanager.archfixture.ui;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.emulator.ColorPaletteImpl;
import dev.tanager.archfixture.ui.theme.Rgb;
import java.awt.Color;
import java.awt.SystemColor;
import java.util.function.Function;
import java.util.function.IntFunction;
import javax.swing.plaf.ColorUIResource;

/** 위반 픽스처: ui.theme 밖에서 색 리터럴을 만든다(테마 토큰만 써야 한다). */
public class PaintsWithLiteral {
    Color made() {
        return new Color(0x1B1E26);
    }

    Color constant() {
        return Color.RED;
    }

    Color decoded() {
        return Color.decode("#1B1E26");
    }

    ColorUIResource uiResource() {
        return new ColorUIResource(0x1B1E26);
    }

    TerminalColor terminalRgb() {
        return TerminalColor.rgb(1, 2, 3);
    }

    IntFunction<Color> constructorRef() {
        return Color::new;
    }

    Function<String, Color> decodeRef() {
        return Color::decode;
    }

    ColorPalette palette() {
        return ColorPaletteImpl.XTERM_PALETTE;
    }

    Color system() {
        return SystemColor.window;
    }

    Rgb rgb() {
        return new Rgb(1, 2, 3);
    }

    Rgb parsed() {
        return Rgb.parse("#010203");
    }
}
