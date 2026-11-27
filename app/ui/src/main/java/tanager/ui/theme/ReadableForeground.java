package tanager.ui.theme;

/**
 * 배경 위에서 읽히지 않는 글자색을 배경 반대쪽(검정 또는 흰색 쪽)으로 옮겨 기준 대비에 맞춘다(#113). 소프트 팔레트는 글자색으로는 눈이
 * 편하지만 같은 색을 배경으로 쓰면 글자와 배경이 모두 밝아져 대비가 무너진다. 결정적이다 — 같은 입력은 같은 결과.
 */
final class ReadableForeground {
    /** WCAG AA 본문 글자 기준. */
    static final double MIN_CONTRAST = 4.5;

    private static final Rgb BLACK = new Rgb(0, 0, 0);
    private static final Rgb WHITE = new Rgb(255, 255, 255);
    private static final int STEPS = 20;

    private ReadableForeground() {}

    /** 이미 기준 이상이면 그대로, 아니면 배경과 더 대비되는 극(검정 · 흰색) 쪽으로 기준에 닿을 때까지 섞은 색. */
    static Rgb on(Rgb foreground, Rgb background) {
        if (Contrast.ratio(foreground, background) >= MIN_CONTRAST) {
            return foreground;
        }
        Rgb pole = Contrast.ratio(BLACK, background) >= Contrast.ratio(WHITE, background) ? BLACK : WHITE;
        for (int step = 1; step < STEPS; step++) {
            Rgb candidate = mix(foreground, pole, (double) step / STEPS);
            if (Contrast.ratio(candidate, background) >= MIN_CONTRAST) {
                return candidate;
            }
        }
        return pole;
    }

    private static Rgb mix(Rgb from, Rgb to, double amount) {
        return new Rgb(
                channel(from.red(), to.red(), amount),
                channel(from.green(), to.green(), amount),
                channel(from.blue(), to.blue(), amount));
    }

    private static int channel(int from, int to, double amount) {
        return (int) Math.round(from + (to - from) * amount);
    }
}
