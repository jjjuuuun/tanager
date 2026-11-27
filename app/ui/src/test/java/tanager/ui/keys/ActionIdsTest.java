package tanager.ui.keys;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tanager.domain.layout.Direction;
import tanager.domain.layout.Orientation;

class ActionIdsTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "pane.splitRight",
                "pane.splitDown",
                "pane.focusLeft",
                "pane.focusRight",
                "pane.focusUp",
                "pane.focusDown",
                "pane.resizeLeft",
                "pane.resizeRight",
                "pane.resizeUp",
                "pane.resizeDown",
                "pane.zoom",
                "pane.close",
                "pane.swapLeft",
                "pane.swapRight",
                "sidebar.toggle",
                "tree.search",
                "settings.open"
            })
    @DisplayName("SDD §8.2 의 동작 id 는 동작으로 읽히고 같은 id 로 돌아온다")
    void roundTrip(String id) {
        // when
        Optional<KeyAction> action = ActionIds.actionOf(id);

        // then
        assertThat(action).isPresent();
        assertThat(ActionIds.idOf(action.get())).isEqualTo(id);
    }

    @Test
    @DisplayName("id 는 동작과 하나씩 맞는다")
    void mapping() {
        // then
        assertThat(ActionIds.actionOf("pane.splitRight")).contains(new LayoutAction.Split(Orientation.ROW));
        assertThat(ActionIds.actionOf("pane.resizeUp")).contains(new LayoutAction.Resize(Direction.UP));
        assertThat(ActionIds.actionOf("pane.swapLeft")).contains(new LayoutAction.Swap(Direction.LEFT));
        assertThat(ActionIds.actionOf("sidebar.toggle")).contains(new KeyAction.ToggleSidebar());
        assertThat(ActionIds.actionOf("tree.search")).contains(new KeyAction.SearchTree());
        assertThat(ActionIds.actionOf("settings.open")).contains(new KeyAction.OpenSettings());
    }

    @Test
    @DisplayName("모르는 id 는 비어 있다(대소문자도 정확히 맞아야 한다)")
    void unknown() {
        // then
        assertThat(ActionIds.actionOf("pane.explode")).isEmpty();
        assertThat(ActionIds.actionOf("Pane.Zoom")).isEmpty();
    }
}
