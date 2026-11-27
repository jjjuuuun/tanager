package tanager.ui.font;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Font;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 설정의 터미널 글꼴 이름을 실제로 쓸 글꼴로 고른다(FR-CFG-02 · FR-UI-06, 리스크 R2). */
class FontChoiceTest {
    private static final TerminalFontSet BUNDLED =
            new TerminalFontSet(new Font("D2Coding", Font.PLAIN, 1), new Font("D2Coding", Font.BOLD, 1));
    private static final TerminalFontSet CONSOLAS =
            new TerminalFontSet(new Font("Consolas", Font.PLAIN, 1), new Font("Consolas", Font.BOLD, 1));

    @Test
    @DisplayName("D2Coding(대소문자 무시)은 찾지 않고 번들 글꼴 객체를 그대로 쓴다")
    void bundledNameUsesBundledFonts() {
        // when
        FontChoice choice = TerminalFonts.choose(
                "d2coding",
                BUNDLED,
                name -> {
                    throw new AssertionError("찾으면 안 된다: " + name);
                },
                font -> true);

        // then
        assertThat(choice.fonts()).isSameAs(BUNDLED);
        assertThat(choice.fallback()).isEmpty();
    }

    @Test
    @DisplayName("한글을 그리는 설치 글꼴이면 그 글꼴을 쓰고 경고가 없다")
    void installedHangulFontIsUsed() {
        // when
        FontChoice choice = TerminalFonts.choose("Consolas", BUNDLED, name -> Optional.of(CONSOLAS), font -> true);

        // then
        assertThat(choice.fonts()).isSameAs(CONSOLAS);
        assertThat(choice.fallback()).isEmpty();
    }

    @Test
    @DisplayName("한글 글리프가 없는 글꼴이면 번들 D2Coding 을 유지하고 '한글 글리프가 없어 D2Coding 을 쓴다' 고 경고한다")
    void fontWithoutHangulKeepsBundled() {
        // when
        FontChoice choice = TerminalFonts.choose("Consolas", BUNDLED, name -> Optional.of(CONSOLAS), font -> false);

        // then
        assertThat(choice.fonts()).isSameAs(BUNDLED);
        assertThat(choice.fallback()).contains(new FontFallback("Consolas", "D2Coding", FontFallback.Reason.NO_HANGUL));
        assertThat(choice.fallback().orElseThrow().message())
                .contains("Consolas")
                .contains("한글 글리프가 없어")
                .contains("D2Coding");
    }

    @Test
    @DisplayName("설치되지 않은 글꼴이면 번들 D2Coding 을 유지하고 찾지 못했다고 경고한다")
    void missingFontKeepsBundled() {
        // when
        FontChoice choice = TerminalFonts.choose("없는글꼴", BUNDLED, name -> Optional.empty(), font -> true);

        // then
        assertThat(choice.fonts()).isSameAs(BUNDLED);
        assertThat(choice.fallback()).contains(new FontFallback("없는글꼴", "D2Coding", FontFallback.Reason.NOT_FOUND));
        assertThat(choice.fallback().orElseThrow().message()).contains("없는글꼴").contains("D2Coding");
    }

    @Test
    @DisplayName("설치 글꼴 찾기는 이름이 같은 패밀리만 인정한다 — JDK 가 대신 준 Dialog 는 없는 글꼴이다")
    void installedLookupRejectsSubstitutes() {
        // when / then
        assertThat(TerminalFonts.installed("Tanager-없는-글꼴-185")).isEmpty();
        assertThat(TerminalFonts.installed(Font.MONOSPACED)).isPresent();
    }
}
