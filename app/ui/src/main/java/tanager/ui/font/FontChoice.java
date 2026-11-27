package tanager.ui.font;

import java.util.Objects;
import java.util.Optional;

/**
 * 설정의 글꼴 이름으로 고른 터미널 글꼴(FR-CFG-02).
 *
 * @param fonts 실제로 쓸 글꼴 한 벌(크기 1 — 쓰는 쪽이 {@link TerminalFontSet#sized})
 * @param fallback 설정한 글꼴을 쓸 수 없어 번들 글꼴을 쓰면 그 경고
 */
public record FontChoice(TerminalFontSet fonts, Optional<FontFallback> fallback) {
    public FontChoice {
        Objects.requireNonNull(fonts, "fonts");
        Objects.requireNonNull(fallback, "fallback");
    }
}
