package tanager.domain.layout;

/** Split 에서 first 가 차지하는 몫. 창이 너무 작아지지 않게 0.1 ~ 0.9 만 허용한다(SDD §5.3). */
public record Ratio(double value) {
    public static final double MIN = 0.1;
    public static final double MAX = 0.9;
    /** 반반. 새로 나눌 때의 비율. */
    public static final Ratio HALF = new Ratio(0.5);

    public Ratio {
        if (Double.isNaN(value) || value < MIN || value > MAX) {
            throw new IllegalArgumentException("비율은 " + MIN + " ~ " + MAX + " 여야 한다: " + value);
        }
    }
}
