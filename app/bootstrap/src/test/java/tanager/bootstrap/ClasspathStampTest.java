package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AOT 캐시를 만들 때의 jar 기록 한 줄(#221). 형식: {@code <sha256> <크기> <수정 시각 ISO-8601> <이름>}. */
class ClasspathStampTest {
    private static final String SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final Instant MODIFIED = Instant.parse("2026-11-28T06:39:07.6868871Z");

    @Test
    @DisplayName("한 줄을 읽는다 — 수정 시각은 100ns 까지 지킨다")
    void parsesLine() {
        // when
        var stamp = ClasspathStamp.parse(SHA + " 4706938 2026-11-28T06:39:07.686887100Z tanager-ui.jar");

        // then
        assertThat(stamp).contains(new ClasspathStamp("tanager-ui.jar", 4706938, SHA, MODIFIED));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "# 주석",
                "abc 10 2026-11-28T06:39:07Z a.jar",
                SHA + " -1 2026-11-28T06:39:07Z a.jar",
                SHA + " ten 2026-11-28T06:39:07Z a.jar",
                SHA + " 10 어제 a.jar",
                SHA + " 10 2026-11-28T06:39:07Z",
                SHA + " 10 2026-11-28T06:39:07Z ../escape.jar",
                SHA + " 10 2026-11-28T06:39:07Z sub\\a.jar"
            })
    @DisplayName("형식이 틀린 줄 · 주석 · 다른 폴더를 가리키는 이름은 건너뛴다")
    void skipsMalformed(String line) {
        // when · then
        assertThat(ClasspathStamp.parse(line)).isEmpty();
    }

    @Test
    @DisplayName("크기가 같고 수정 시각만 다르면 시각이 밀린 것이다(설치 · 압축 풀기)")
    void driftedWhenOnlyTimeDiffers() {
        // given
        ClasspathStamp stamp = new ClasspathStamp("a.jar", 10, SHA, MODIFIED);

        // when · then
        assertThat(stamp.drifted(10, MODIFIED.plusSeconds(9 * 3600))).isTrue();
        assertThat(stamp.drifted(10, MODIFIED)).isFalse();
        assertThat(stamp.drifted(11, MODIFIED.plusSeconds(1)))
                .as("크기가 다르면 다른 파일")
                .isFalse();
    }
}
