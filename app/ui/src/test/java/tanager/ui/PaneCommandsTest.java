package tanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.layout.Direction;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.Edge;
import tanager.domain.layout.LayoutError;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Rect;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.Workspace;
import tanager.ui.keys.LayoutAction;

class PaneCommandsTest {
    private static final Rect AREA = new Rect(0, 0, 800, 600);
    private static final PaneId FOCUSED = new PaneId("p3");
    private static final ShellProfile POWERSHELL = new ShellProfile(
            new ProfileId("powershell"),
            "powershell",
            ShellKind.WINDOWS_POWERSHELL,
            new ShellEnvironment.Windows(),
            new LaunchTemplate("powershell.exe", List.of()),
            IntegrationMode.NONE);

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

    @Test
    @DisplayName("머리줄 · 메뉴의 동작은 그 창에 먼저 포커스한 뒤 부른다(#149)")
    void runOnFocusesFirst() {
        // given
        FakePanes panes = new FakePanes();

        // when
        panes.commands().runOn(new PaneId("p2"), new LayoutAction.Zoom(), AREA, 6);

        // then
        assertThat(panes.calls).containsExactly("focus p2", "zoom");
    }

    @Test
    @DisplayName("머리줄 · 메뉴의 닫기는 포커스를 옮기지 않고 그 창을 닫는다")
    void runOnCloseClosesThatPane() {
        // given
        FakePanes panes = new FakePanes();

        // when
        panes.commands().runOn(new PaneId("p2"), new LayoutAction.Close(), AREA, 6);

        // then
        assertThat(panes.calls).containsExactly("close p2");
    }

    @Test
    @DisplayName("포커스가 실패하면(닫힌 창) 동작을 부르지 않는다")
    void runOnStopsWhenFocusFails() {
        // given
        FakePanes panes = new FakePanes();
        panes.reply = Result.err(new LayoutError.UnknownPane(new PaneId("p2")));

        // when
        panes.commands().runOn(new PaneId("p2"), new LayoutAction.Split(Orientation.ROW), AREA, 6);

        // then
        assertThat(panes.calls).containsExactly("focus p2");
    }

    @Test
    @DisplayName("앞쪽 분할: 그 창에 포커스 → 분할 → 새 창을 그 창의 왼쪽(ROW) · 위(COLUMN) 변으로 옮긴다")
    void splitBeforeMovesNewPaneToLeadingEdge() {
        // given
        FakePanes panes = new FakePanes();
        // 분할 응답의 포커스 창 = 새 창
        Workspace split = Workspace.initial()
                .split(Orientation.ROW, Optional.empty(), Optional.empty())
                .workspace();
        panes.reply = Result.ok(split);

        // when
        panes.commands().splitBefore(new PaneId("p1"), Orientation.ROW);
        panes.commands().splitBefore(new PaneId("p1"), Orientation.COLUMN);

        // then
        assertThat(panes.calls)
                .containsExactly(
                        "focus p1",
                        "split ROW",
                        "move p2 " + new DropZone.Side(new PaneId("p1"), Edge.LEFT),
                        "focus p1",
                        "split COLUMN",
                        "move p2 " + new DropZone.Side(new PaneId("p1"), Edge.TOP));
    }

    @Test
    @DisplayName("다른 프로파일로 분할: 그 창에 포커스한 뒤 그 셸로 오른쪽 분할")
    void splitWithProfile() {
        // given
        FakePanes panes = new FakePanes();

        // when
        panes.commands().splitWith(new PaneId("p1"), POWERSHELL);

        // then
        assertThat(panes.calls).containsExactly("focus p1", "split ROW powershell");
    }
}
