package dev.tanager.application.port.out;

/** 터미널 크기(열 · 행). 두 값 모두 1..32767 — ConPTY 의 COORD 가 16비트 부호 있는 정수라서. */
public record TerminalSize(int columns, int rows) {
    private static final int MAX = Short.MAX_VALUE;

    /** 크기를 아직 모를 때 쓰는 기본값. */
    public static final TerminalSize DEFAULT = new TerminalSize(80, 24);

    public TerminalSize {
        requireInRange(columns, "열");
        requireInRange(rows, "행");
    }

    private static void requireInRange(int value, String name) {
        if (value < 1 || value > MAX) {
            throw new IllegalArgumentException(name + " 수는 1.." + MAX + " 이어야 한다: " + value);
        }
    }
}
