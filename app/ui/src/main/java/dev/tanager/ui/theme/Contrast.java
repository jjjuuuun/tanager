package dev.tanager.ui.theme;

/** WCAG 2.x 대비 비율. */
final class Contrast {
    private Contrast() {}

    /** 1(같은 휘도) ~ 21(검정 · 흰색). 인자 순서와 무관하다. */
    static double ratio(Rgb a, Rgb b) {
        double la = a.relativeLuminance();
        double lb = b.relativeLuminance();
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
}
