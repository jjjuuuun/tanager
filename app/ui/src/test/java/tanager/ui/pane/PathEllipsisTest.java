package tanager.ui.pane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 머리줄 경로를 폭(글자 수)에 맞게 가운데를 줄인다(#82). 글자는 코드 포인트로 센다. */
class PathEllipsisTest {

    @Test
    @DisplayName("폭에 들어가면 그대로 둔다")
    void keepsPathThatFits() {
        assertThat(PathEllipsis.fit("/home/me", 30)).isEqualTo("/home/me");
    }

    @Test
    @DisplayName("WSL 경로는 /mnt/<드라이브> 와 뒤쪽 폴더를 남기고 가운데를 줄인다")
    void keepsMountRootAndTrailingSegments() {
        // when
        String fitted = PathEllipsis.fit("/mnt/c/Users/user/Desktop/Tanager", 30);

        // then
        assertThat(fitted).isEqualTo("/mnt/c/…/user/Desktop/Tanager");
        assertThat(fitted.codePointCount(0, fitted.length())).isLessThanOrEqualTo(30);
    }

    @Test
    @DisplayName("Windows 경로는 드라이브와 뒤쪽 폴더를 남긴다")
    void keepsDriveAndTrailingSegments() {
        assertThat(PathEllipsis.fit("C:\\Users\\user\\Desktop\\Tanager\\app\\ui", 20))
                .isEqualTo("C:\\…\\Tanager\\app\\ui");
    }

    @Test
    @DisplayName("일반 리눅스 경로는 첫 폴더를 루트로 남긴다")
    void keepsFirstSegmentAsRoot() {
        assertThat(PathEllipsis.fit("/home/me/projects/tanager/app", 22)).isEqualTo("/home/…/tanager/app");
    }

    @Test
    @DisplayName("루트와 마지막 폴더가 함께 들어가지 않으면 마지막 폴더만 남긴다")
    void dropsRootWhenOnlyLastSegmentFits() {
        assertThat(PathEllipsis.fit("/mnt/c/Users/user/Desktop/Tanager", 10)).isEqualTo("…/Tanager");
    }

    @Test
    @DisplayName("마지막 폴더도 넘치면 끝을 … 로 자른다")
    void truncatesLastSegmentEnd() {
        assertThat(PathEllipsis.fit("/a/b/abcdefghijklmnop", 8)).isEqualTo("abcdefg…");
    }

    @Test
    @DisplayName("한글 · 서로게이트 쌍은 코드 포인트 단위로 자르고 깨지지 않는다")
    void cutsByCodePoint() {
        // when
        String korean = PathEllipsis.fit("/집/문서/가나다라마바사아자차", 6);
        String astral = PathEllipsis.fit("/x/𝒜𝒜𝒜𝒜𝒜𝒜", 4);

        // then
        assertThat(korean).isEqualTo("가나다라마…");
        assertThat(astral).isEqualTo("𝒜𝒜𝒜…");
        assertThat(astral.codePointCount(0, astral.length())).isEqualTo(4);
        assertThat(astral.codePoints().noneMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE))
                .isTrue();
    }

    @Test
    @DisplayName("홈 경로 ~ 도 그대로 다룬다")
    void handlesHomeShorthand() {
        assertThat(PathEllipsis.fit("~", 5)).isEqualTo("~");
    }

    @Test
    @DisplayName("폭이 1 보다 작으면 거부한다")
    void rejectsNonPositiveWidth() {
        assertThatThrownBy(() -> PathEllipsis.fit("/a", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
