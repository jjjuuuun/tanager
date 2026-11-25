package dev.tanager.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ThemeFileTest {
    @Test
    @DisplayName("'@이름 = #hex' 줄만 읽고 주석 · 다른 키는 무시한다")
    void parsesVariableLinesOnly() {
        // given
        String text = """
                # comment
                @window = #1B1E26
                @text=#D6DAE3
                Button.arc = 8
                """;

        // when
        ThemeFile file = ThemeFile.parse(text);

        // then
        assertThat(file.variables()).containsKeys("@window", "@text").doesNotContainKey("Button.arc");
        assertThat(file.resolve("@window")).isEqualTo(Rgb.parse("#1B1E26"));
    }

    @Test
    @DisplayName("@참조를 따라가 색을 구한다")
    void resolvesReferences() {
        ThemeFile file = ThemeFile.parse("@a = #102030\n@b = @a\n@c = @b\n");

        assertThat(file.resolve("@c")).isEqualTo(Rgb.parse("#102030"));
    }

    @Test
    @DisplayName("순환 참조는 예외")
    void cycleThrows() {
        ThemeFile file = ThemeFile.parse("@a = @b\n@b = @a\n");

        assertThatThrownBy(() -> file.resolve("@a")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("정의되지 않은 변수는 예외")
    void undefinedThrows() {
        ThemeFile file = ThemeFile.parse("@a = @missing\n");

        assertThatThrownBy(() -> file.resolve("@a")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> file.resolve("@nothing")).isInstanceOf(RuntimeException.class);
    }
}
