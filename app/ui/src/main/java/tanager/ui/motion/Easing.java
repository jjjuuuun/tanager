package tanager.ui.motion;

/** 전환 진행률(시간 비율) → 값 비율. 순수 함수(FR-UI-04). */
public final class Easing {
    private Easing() {}

    /** 세제곱 ease-out: 처음이 빠르고 끝에서 느려진다. {@code t} 는 0..1 로 자르고(NaN 은 0), 0 → 0, 1 → 1, 단조 증가. */
    public static double easeOut(double t) {
        double clamped = Lerp.clamp(t);
        double rest = 1 - clamped;
        return 1 - rest * rest * rest;
    }
}
