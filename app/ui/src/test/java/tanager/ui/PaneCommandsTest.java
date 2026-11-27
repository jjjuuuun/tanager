package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tanager.domain.layout.Direction;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Rect;
import tanager.domain.shared.PaneId;
import tanager.ui.keys.LayoutAction;

class PaneCommandsTest {
    private static final Rect AREA = new Rect(0, 0, 800, 600);
    private static final PaneId FOCUSED = new PaneId("p3");

    static Stream<Arguments> actions() {
        return Stream.of(
                Arguments.of(new LayoutAction.Split(Orientation.ROW), "split ROW"),
                Arguments.of(new LayoutAction.Split(Orientation.COLUMN), "split COLUMN"),
                Arguments.of(new LayoutAction.Focus(Direction.RIGHT), "focus RIGHT " + AREA + " 6"),
                Arguments.of(new LayoutAction.Resize(Direction.UP), "resize UP"),
                Arguments.of(new LayoutAction.Zoom(), "zoom"),
                Arguments.of(new LayoutAction.Close(), "close p3"),
                Arguments.of(new LayoutAction.Swap(Direction.LEFT), "swap LEFT " + AREA + " 6"));
    }

    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @MethodSource("actions")
    @DisplayName("동작마다 그 유스케이스를 한 번 부른다 — 방향 포커스 · 교환은 창 영역과 간격을, 닫기는 포커스 창을 넘긴다")
    void runCallsUseCase(LayoutAction action, String expected) {
        // given
        FakePanes panes = new FakePanes();

        // when
        panes.commands().run(action, FOCUSED, AREA, 6);

        // then
        assertThat(panes.calls).containsExactly(expected);
    }
}
