package dev.tanager.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 색 문자열은 ui.theme 밖의 ui 소스에 두지 않는다(ArchUnit 이 못 보는 문자열 속 색, SDD §8.5 · ADR-0010). */
class ColorLiteralSourceTest {
    // Gradle 은 테스트를 모듈 폴더(app/architecture)에서 돌린다
    private static final Path UI_SOURCES = Path.of("..", "ui", "src", "main", "java");
    private static final Path THEME = UI_SOURCES.resolve(Path.of("dev", "tanager", "ui", "theme"));

    @Test
    @DisplayName("ui.theme 밖의 ui 소스 문자열에는 색 값이 없다")
    void uiSourcesOutsideThemeHaveNoColorStrings() throws IOException {
        // given
        assertThat(UI_SOURCES).as("ui 소스 폴더").isDirectory();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(UI_SOURCES)) {
            files = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getParent().equals(THEME))
                    .toList();
        }

        // when
        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            for (String color : ColorLiterals.find(Files.readString(file, StandardCharsets.UTF_8))) {
                violations.add(UI_SOURCES.relativize(file) + ": " + color);
            }
        }

        // then
        assertThat(files).as("검사한 파일(공허하게 통과하지 않게)").isNotEmpty();
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("문자열 속 #RGB · #RRGGBB · #RRGGBBAA 와 FlatLaf 스타일 문자열을 찾는다")
    void findsColorStrings() {
        assertThat(ColorLiterals.find("var c = \"#1B1E26\";")).containsExactly("#1B1E26");
        assertThat(ColorLiterals.find("x.putClientProperty(\"FlatLaf.style\", \"background: #fff; arc: 8\");"))
                .containsExactly("#fff");
        assertThat(ColorLiterals.find("String s = \"#1B1E26CC\";")).containsExactly("#1B1E26CC");
        assertThat(ColorLiterals.find("String t = \"\"\"\n    border: 1,1,1,1,#262A35\n    \"\"\";"))
                .containsExactly("#262A35");
        assertThat(ColorLiterals.find("String e = \"say \\\"#abc\\\"\";")).containsExactly("#abc");
    }

    @Test
    @DisplayName("주석 · 문자 리터럴 · 색이 아닌 # 은 무시한다")
    void ignoresCommentsAndNonColors() {
        assertThat(ColorLiterals.find("// 배경 #1B1E26\nint a = 1;")).isEmpty();
        assertThat(ColorLiterals.find("/* #FFFFFF */ int b = 2;")).isEmpty();
        assertThat(ColorLiterals.find("char c = '#'; String d = \"issue #45\";"))
                .isEmpty();
        assertThat(ColorLiterals.find("String g = \"#GGGGGG\"; String h = \"#1B1E26zz\";"))
                .isEmpty();
        assertThat(ColorLiterals.find("int hex = 0x1B1E26;")).isEmpty();
    }
}
