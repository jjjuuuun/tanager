package dev.tanager.ui.theme;

import java.util.List;
import java.util.Objects;

/** 터미널 색: 전경 · 배경(테마의 term) · 커서 · 선택 배경 · ANSI 16색. */
record TerminalPalette(Rgb foreground, Rgb background, Rgb cursor, Rgb selection, List<Rgb> ansi) {
    static final int ANSI_COLORS = 16;

    public TerminalPalette {
        Objects.requireNonNull(foreground, "foreground");
        Objects.requireNonNull(background, "background");
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(selection, "selection");
        ansi = List.copyOf(ansi);
        if (ansi.size() != ANSI_COLORS) {
            throw new IllegalArgumentException("ANSI 색은 16개여야 한다: " + ansi.size());
        }
    }

    /** ANSI 색 0..15. */
    public Rgb ansi(int index) {
        return ansi.get(index);
    }
}
