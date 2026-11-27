package tanager.ui.terminal;

import tanager.application.port.out.TerminalSize;

/** JediTerm 의 열 · 행을 {@link TerminalSize} 의 범위(1..32767)로 맞춘다. */
final class TerminalSizes {
    private TerminalSizes() {}

    static TerminalSize of(int columns, int rows) {
        return new TerminalSize(clamp(columns), clamp(rows));
    }

    private static int clamp(int value) {
        return Math.clamp(value, 1, Short.MAX_VALUE);
    }
}
