package tanager.ui.theme;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.StyleState;
import java.util.Objects;
import java.util.Optional;

/**
 * 셸이 고른 ANSI 글자색 · 배경색이 읽히지 않으면 그릴 글자색을 보정하는 스타일 상태(#113).
 *
 * <p>JediTerm 은 글자색 계산을 private 메서드로 그려 끼어들 수 없다. 대신 SGR 마다 거치는 {@link #setCurrent} 에서 고친다.
 * 에뮬레이터는 지금 스타일에서 다음 스타일을 만들므로({@code getCurrent()} → 수정 → {@code setCurrent()}), 보정한 색은 원래 색을
 * 기억하는 {@link Adjusted} 로 둔다. 다음 {@code setCurrent} 에서 원래 색으로 되돌린 뒤 다시 판단한다 — 배경이 기본으로 돌아오면
 * 원래 색 번호로 돌아간다.
 *
 * <p>보정 대상은 글자색 · 배경색이 모두 ANSI 16색(글자색은 기본 전경 포함)인 경우뿐이다. truecolor · 256색 · 기본 배경 · 반전은
 * 사용자가 고른 그대로 둔다.
 */
final class ReadableStyleState extends StyleState {
    private static final int ANSI_COLORS = TerminalPalette.ANSI_COLORS;

    private final TerminalPalette palette;

    ReadableStyleState(TerminalPalette palette) {
        this.palette = Objects.requireNonNull(palette, "palette");
    }

    @Override
    public void setCurrent(TextStyle current) {
        super.setCurrent(readable(original(current)));
    }

    private TextStyle readable(TextStyle style) {
        TerminalColor background = style.getBackground();
        TerminalColor foreground = style.getForeground();
        if (background == null
                || !isAnsi(background)
                || (foreground != null && !isAnsi(foreground))
                || style.hasOption(TextStyle.Option.INVERSE)) {
            return style;
        }
        Rgb shown = foreground == null ? palette.foreground() : palette.ansi(foreground.getColorIndex());
        Rgb readable = ReadableForeground.on(shown, palette.ansi(background.getColorIndex()));
        if (readable.equals(shown)) {
            return style;
        }
        return new TextStyle.Builder(style)
                .setForeground(new Adjusted(Optional.ofNullable(foreground), readable))
                .build();
    }

    private static TextStyle original(TextStyle style) {
        if (style.getForeground() instanceof Adjusted adjusted) {
            return new TextStyle.Builder(style)
                    .setForeground(adjusted.original.orElse(null))
                    .build();
        }
        return style;
    }

    private static boolean isAnsi(TerminalColor color) {
        return color.isIndexed() && color.getColorIndex() < ANSI_COLORS;
    }

    /** 보정한 글자색. 다음 스타일에서 원래 색(없으면 기본 전경)으로 되돌리려고 기억한다. */
    private static final class Adjusted extends TerminalColor {
        private final Optional<TerminalColor> original;

        Adjusted(Optional<TerminalColor> original, Rgb shown) {
            super(new Color(shown.red(), shown.green(), shown.blue()));
            this.original = original;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Adjusted that && super.equals(that) && original.equals(that.original);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), original);
        }
    }
}
