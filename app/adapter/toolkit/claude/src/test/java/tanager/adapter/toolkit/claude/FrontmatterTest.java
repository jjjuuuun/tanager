package tanager.adapter.toolkit.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.shared.Result;

class FrontmatterTest {

    private static Result<Frontmatter, FrontmatterError> read(String text) throws IOException {
        return Frontmatter.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Frontmatter ok(String text) throws IOException {
        Result<Frontmatter, FrontmatterError> result = read(text);
        assertThat(result.error()).isEmpty();
        return result.value().orElseThrow();
    }

    @Test
    @DisplayName("첫 줄이 --- 가 아니면 프런트매터가 없는 빈 값이다")
    void noFrontmatter() throws IOException {
        // when
        Frontmatter fm = ok("# Title\nname: not-frontmatter\n");

        // then
        assertThat(fm.text("name", 100)).isEmpty();
    }

    @Test
    @DisplayName("--- 사이의 YAML 에서 글자 · 숫자 · 참거짓 값을 글자로 읽는다")
    void readsScalars() throws IOException {
        // when
        Frontmatter fm = ok("---\nname: tdd\nversion: 3\nhidden: true\nempty:\n---\nbody\n");

        // then
        assertThat(fm.text("name", 100)).contains("tdd");
        assertThat(fm.text("version", 100)).contains("3");
        assertThat(fm.text("hidden", 100)).contains("true");
        assertThat(fm.text("empty", 100)).isEmpty();
        assertThat(fm.text("missing", 100)).isEmpty();
    }

    @Test
    @DisplayName("BOM 과 CRLF 줄바꿈이 있어도 프런트매터를 알아본다")
    void bomAndCrlf() throws IOException {
        // when
        Frontmatter fm = ok("﻿---\r\nname: windows\r\n---\r\nbody\r\n");

        // then
        assertThat(fm.text("name", 100)).contains("windows");
    }

    @Test
    @DisplayName("목록 값은 쉼표로 잇고, 중첩 맵은 글자로 읽지 않는다")
    void listsAndMaps() throws IOException {
        // when
        Frontmatter fm = ok("---\ntools:\n  - Read\n  - Grep\nmetadata:\n  k: v\n---\n");

        // then
        assertThat(fm.text("tools", 100)).contains("Read, Grep");
        assertThat(fm.text("metadata", 100)).isEmpty();
    }

    @Test
    @DisplayName("글자 값의 제어 문자 · 줄바꿈은 공백 하나로 펴고, 상한을 넘으면 … 로 자른다")
    void flattensAndClips() throws IOException {
        // when
        Frontmatter fm = ok("---\nd: \"a\\n\\tb\\u0007  c\"\nlong: abcdefghij\n---\n");

        // then
        assertThat(fm.text("d", 100)).contains("a b c");
        assertThat(fm.text("long", 5)).contains("abcd…");
        assertThat(fm.text("long", 10)).contains("abcdefghij");
    }

    @Test
    @DisplayName("닫는 --- 가 없으면 1번 줄의 형식 오류다")
    void unclosed() throws IOException {
        // when / then
        assertThat(read("---\nname: x\n").error()).contains(new FrontmatterError(OptionalInt.of(1)));
    }

    @Test
    @DisplayName("YAML 이 깨지면 그 파일 안의 줄 번호(여는 --- 가 1번 줄)를 준다")
    void brokenYamlLine() throws IOException {
        // when / then
        assertThat(read("---\ndescription: fine\nname: \"unterminated\n---\n").error())
                .contains(new FrontmatterError(OptionalInt.of(3)));
    }

    @Test
    @DisplayName("YAML 의 맨 위가 맵이 아니면 줄 번호 없는 형식 오류다")
    void notAMapping() throws IOException {
        // when / then
        assertThat(read("---\n- a\n- b\n---\n").error()).contains(new FrontmatterError(OptionalInt.empty()));
    }

    @Test
    @DisplayName("비어 있는 프런트매터는 빈 값이다")
    void emptyFrontmatter() throws IOException {
        // when
        Frontmatter fm = ok("---\n---\nbody\n");

        // then
        assertThat(fm.text("name", 100)).isEmpty();
    }
}
