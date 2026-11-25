package dev.tanager.archfixture.ui;

import java.awt.Color;

/** 위반 픽스처: ui.theme 밖에서 색 리터럴을 만든다(테마 토큰만 써야 한다). */
public class PaintsWithLiteral {
    Color made() {
        return new Color(0x1B1E26);
    }

    Color constant() {
        return Color.RED;
    }
}
