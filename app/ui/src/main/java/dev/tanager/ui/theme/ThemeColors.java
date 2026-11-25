package dev.tanager.ui.theme;

import java.awt.Color;
import javax.swing.UIManager;

/** UI 코드가 색을 얻는 유일한 입구. 값은 설치된 소프트 테마의 {@code Tanager.*} 키에서 온다. */
public final class ThemeColors {
    private ThemeColors() {}

    /** 토큰의 현재 색. 테마가 설치되지 않아 값이 없으면 {@link IllegalStateException}(버그). */
    public static Color get(ColorToken token) {
        Color color = UIManager.getColor(token.key());
        if (color == null) {
            throw new IllegalStateException("테마 색 없음: " + token.key() + " — 소프트 테마가 설치되지 않았다");
        }
        return color;
    }
}
