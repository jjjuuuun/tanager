package tanager.ui.motion;

/** 두 값 사이의 선형 보간. 비율은 0..1 로 자른다. 색 섞기는 {@code ThemeColors.mix} 에 있다(색은 ui.theme 에서만 만든다). */
public final class Lerp {
    private Lerp() {}

    /** {@code from} 에서 {@code to} 쪽으로 {@code fraction} 만큼. 0 이면 정확히 {@code from}, 1 이면 정확히 {@code to}. */
    public static double number(double from, double to, double fraction) {
        double f = clamp(fraction);
        return f >= 1 ? to : from + (to - from) * f;
    }

    /** {@link #number} 를 반올림한 정수. 결과는 항상 두 끝 값 사이다. */
    public static int integer(int from, int to, double fraction) {
        return (int) Math.round(number(from, to, fraction));
    }

    /** 0..1 로 자른다. NaN 은 0. */
    static double clamp(double fraction) {
        if (!(fraction > 0)) {
            return 0;
        }
        return Math.min(fraction, 1);
    }
}
