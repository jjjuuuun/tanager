package tanager.adapter.toolkit.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarkdownTextTest {

    private static MarkdownText read(String text) throws IOException {
        return MarkdownText.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("줄 수는 마지막 줄바꿈이 없어도 그 줄을 센다 · CRLF 도 한 줄 · 빈 파일은 0")
    void countsLines() throws IOException {
        // when / then
        assertThat(read("a\nb\n").lines()).isEqualTo("2");
        assertThat(read("a\nb").lines()).isEqualTo("2");
        assertThat(read("a\r\nb\r\n").lines()).isEqualTo("2");
        assertThat(read("").lines()).isEqualTo("0");
        assertThat(read("\n").lines()).isEqualTo("1");
    }

    @Test
    @DisplayName("첫 제목은 앞의 프런트매터와 코드 블록 안의 # 을 건너뛰고, 닫는 # 을 뗀다")
    void firstHeading() throws IOException {
        // given
        String text = "---\ntitle: x\n# not heading\n---\n```sh\n# comment\n```\nplain\n## Real title ##\n# Later\n";

        // when
        MarkdownText markdown = read(text);

        // then
        assertThat(markdown.heading()).contains("Real title");
    }

    @Test
    @DisplayName("#해시태그 · 들여쓰기 4칸은 제목이 아니고, 제목이 없으면 empty")
    void notHeadings() throws IOException {
        // when / then
        assertThat(read("#hashtag\n    # indented code\ntext\n").heading()).isEmpty();
        assertThat(read("   # three spaces\n").heading()).contains("three spaces");
    }

    @Test
    @DisplayName("제목은 한 줄로 펴고 80 코드 포인트로 자른다")
    void headingIsClipped() throws IOException {
        // given
        String longTitle = "가".repeat(120);

        // when
        String heading = read("# " + longTitle + "\n").heading().orElseThrow();

        // then
        assertThat(heading.codePointCount(0, heading.length())).isEqualTo(MarkdownText.MAX_HEADING_CODE_POINTS);
        assertThat(MarkdownText.MAX_HEADING_CODE_POINTS).isEqualTo(80);
        assertThat(heading).endsWith("…");
    }

    @Test
    @DisplayName("import 는 줄 앞 · 공백 뒤의 @경로만 센다 — 메일 주소 · 코드 스팬 · 코드 블록은 세지 않는다")
    void countsImports() throws IOException {
        // given
        String text = String.join(
                "\n",
                "@AGENTS.md",
                "See @README for overview and @package.json for scripts.",
                "Mail dev@example.com about it.",
                "Literal `@not/imported.md` here, ``@also `not` imported``.",
                "- @Design\\ Docs/api.md",
                "~~~",
                "@fenced/one.md",
                "~~~",
                "@ alone",
                "");

        // when
        MarkdownText markdown = read(text);

        // then
        assertThat(markdown.imports()).isEqualTo(4);
    }

    @Test
    @DisplayName("1MB 를 넘으면 앞 1MB 의 줄 수에 > 를 붙인다")
    void truncatedLines() throws IOException {
        // given
        String line = "x".repeat(1023) + "\n";
        String text = line.repeat(1024) + "tail\n";

        // when
        MarkdownText markdown = read(text);

        // then
        assertThat(MarkdownText.MAX_BYTES).isEqualTo(1024 * 1024);
        assertThat(markdown.lines()).isEqualTo(">1024");
    }

    @Test
    @DisplayName("정확히 1MB 면 자르지 않는다")
    void exactlyOneMegabyte() throws IOException {
        // given
        String text = ("x".repeat(1023) + "\n").repeat(1024);

        // when / then
        assertThat(read(text).lines()).isEqualTo("1024");
    }

    @Test
    @DisplayName("BOM 이 있어도 첫 줄의 제목을 읽는다")
    void bom() throws IOException {
        // when / then
        assertThat(read("﻿# Title\n").heading()).contains("Title");
    }
}
