package dev.tanager.archfixture.ui.theme;

import java.awt.Color;

/** 허용 픽스처: ui.theme 안에서는 색을 만들어도 된다. */
public class ThemeOwnsColors {
    Color made() {
        return new Color(0x1B1E26);
    }

    Color constant() {
        return Color.RED;
    }
}
