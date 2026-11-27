package tanager.archfixture.ui;

import com.jediterm.terminal.TerminalColor;
import java.awt.Color;

/** 허용 픽스처: ANSI 번호로 색을 가리키거나 받은 색을 읽기만 하는 것은 색을 만드는 것이 아니다. */
public class UsesIndexedColor {
    TerminalColor ansi() {
        return TerminalColor.index(1);
    }

    int red(Color given) {
        return given.getRed();
    }
}
