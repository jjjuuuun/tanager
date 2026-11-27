package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tanager.domain.layout.Rect;
import tanager.domain.workspace.Bounds;

/**
 * 저장된 창 자리를 지금 모니터들에 둘 수 있는가(FR-RES-03, #180). 제목 표시줄이 모니터 영역의 합 안에 가로 48px 이상 · 세로로 전부 보여야
 * 그 자리다.
 */
class WindowPlacementTest {
    private static final Rect PRIMARY = new Rect(0, 0, 1920, 1080);
    // 주 모니터 왼쪽의 모니터 — 좌표가 음수다
    private static final Rect LEFT = new Rect(-1920, 0, 1920, 1080);
    private static final Rect RIGHT = new Rect(1920, 0, 1920, 1080);
    private static final Rect BELOW = new Rect(0, 1080, 1920, 1080);
    private static final int TITLE = WindowPlacement.TITLE_BAR_HEIGHT;

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("주 모니터 안", new Bounds(100, 100, 800, 600), List.of(PRIMARY), true),
                Arguments.of("오른쪽 끝에 정확히 48px 걸침", new Bounds(1920 - 48, 100, 800, 600), List.of(PRIMARY), true),
                Arguments.of("오른쪽 끝에 47px 만 걸침", new Bounds(1920 - 47, 100, 800, 600), List.of(PRIMARY), false),
                Arguments.of("왼쪽 끝에 48px 걸침", new Bounds(48 - 800, 100, 800, 600), List.of(PRIMARY), true),
                Arguments.of("왼쪽 끝에 47px 만 걸침", new Bounds(47 - 800, 100, 800, 600), List.of(PRIMARY), false),
                Arguments.of("제목 표시줄이 위로 1px 넘침", new Bounds(100, -1, 800, 600), List.of(PRIMARY), false),
                Arguments.of("제목 표시줄이 아래 끝에 딱 맞음", new Bounds(100, 1080 - TITLE, 800, 600), List.of(PRIMARY), true),
                Arguments.of(
                        "제목 표시줄이 아래로 1px 넘침", new Bounds(100, 1080 - TITLE + 1, 800, 600), List.of(PRIMARY), false),
                Arguments.of("두 모니터 사이에 걸침", new Bounds(1900, 100, 800, 600), List.of(PRIMARY, RIGHT), true),
                Arguments.of(
                        "위아래 두 모니터 사이에 제목 표시줄이 걸침",
                        new Bounds(100, 1080 - TITLE / 2, 800, 600),
                        List.of(PRIMARY, BELOW),
                        true),
                Arguments.of("음수 좌표 모니터 안", new Bounds(-1500, 200, 800, 600), List.of(LEFT, PRIMARY), true),
                Arguments.of("음수 좌표 모니터를 뺐음", new Bounds(-1500, 200, 800, 600), List.of(PRIMARY), false),
                Arguments.of("오른쪽 모니터를 뺐음", new Bounds(2200, 100, 800, 600), List.of(PRIMARY), false),
                Arguments.of("모니터를 모름", new Bounds(100, 100, 800, 600), List.of(), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("제목 표시줄이 가로 48px 이상 · 세로로 전부 보이면 저장된 자리, 아니면 empty(주 모니터 기본 자리)")
    void fitsWhenTitleBarIsVisible(String name, Bounds saved, List<Rect> screens, boolean fits) {
        // when
        Optional<Bounds> placed = WindowPlacement.fit(saved, screens);

        // then
        if (fits) {
            assertThat(placed).contains(saved);
        } else {
            assertThat(placed).isEmpty();
        }
    }
}
