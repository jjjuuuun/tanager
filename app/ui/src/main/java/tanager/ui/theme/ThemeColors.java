package tanager.ui.theme;

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

    /** 두 토큰 색 사이(전환 효과, FR-UI-04). {@code fraction} 0 이면 {@code from}, 1 이면 {@code to} 의 색이다. */
    public static Color mix(ColorToken from, ColorToken to, double fraction) {
        return mix(get(from), get(to), fraction);
    }

    /** 채널(알파 포함)마다 선형 보간하고 반올림한다. {@code fraction} 은 0..1 로 자른다(NaN 은 0). */
    static Color mix(Color from, Color to, double fraction) {
        double f = fraction > 0 ? Math.min(fraction, 1) : 0;
        return new Color(
                channel(from.getRed(), to.getRed(), f),
                channel(from.getGreen(), to.getGreen(), f),
                channel(from.getBlue(), to.getBlue(), f),
                channel(from.getAlpha(), to.getAlpha(), f));
    }

    private static int channel(int from, int to, double fraction) {
        return (int) Math.round(from + (to - from) * fraction);
    }

    /**
     * 토큰 색에 불투명도 {@code alpha}(0 ~ 255)를 입힌 색. 창 위에 겹쳐 그리는 미리보기(끌어다 놓을 곳, #151)가 밑의 글자를 가리지 않게
     * 쓴다.
     */
    public static Color translucent(ColorToken token, int alpha) {
        if (alpha < 0 || alpha > 255) {
            throw new IllegalArgumentException("불투명도는 0 ~ 255 다: " + alpha);
        }
        Color color = get(token);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
