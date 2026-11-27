package tanager.ui.keys;

import static java.awt.event.InputEvent.ALT_DOWN_MASK;
import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tanager.application.port.out.KeyConfig;
import tanager.domain.layout.Direction;
import tanager.domain.layout.Orientation;

/** 기본 키 위에 사용자 재정의를 얹는다(FR-CFG-01, #183). 예외 없이 문제를 목록으로 돌려준다. */
class KeymapResolveTest {
    private static final int ALT_SHIFT = ALT_DOWN_MASK | SHIFT_DOWN_MASK;
    private static final int CTRL_SHIFT = CTRL_DOWN_MASK | SHIFT_DOWN_MASK;

    private static KeymapResolution resolve(Map<String, String> bindings) {
        return Keymap.resolve(Keymap.defaults(), new KeyConfig(Optional.empty(), bindings));
    }

    @Test
    @DisplayName("재정의가 없으면 기본 키 그대로이고 문제가 없다")
    void noOverrides() {
        // when
        KeymapResolution resolution = Keymap.resolve(Keymap.defaults(), KeyConfig.defaults());

        // then
        assertThat(resolution.problems()).isEmpty();
        assertThat(resolution.keymap().bindings()).isEqualTo(Keymap.defaults().bindings());
    }

    @Test
    @DisplayName("기본값에 사이드바 토글 · 트리 검색 · 설정 열기 키가 있다")
    void appDefaults() {
        // given
        Keymap keymap = Keymap.defaults();

        // then
        assertThat(keymap.actionFor(KeyEvent.VK_E, CTRL_SHIFT)).contains(new KeyAction.ToggleSidebar());
        assertThat(keymap.actionFor(KeyEvent.VK_F, CTRL_SHIFT)).contains(new KeyAction.SearchTree());
        assertThat(keymap.actionFor(KeyEvent.VK_COMMA, CTRL_DOWN_MASK)).contains(new KeyAction.OpenSettings());
        assertThat(keymap.keyOf(new KeyAction.OpenSettings())).contains("Ctrl+,");
    }

    @Test
    @DisplayName("오른쪽 분할을 Ctrl+Shift+D 로 바꾸면 그 키가 분할이고 Alt+Shift+= 는 셸로 간다")
    void overrideReplacesDefault() {
        // when
        KeymapResolution resolution = resolve(Map.of("pane.splitRight", "ctrl shift D"));

        // then
        Keymap keymap = resolution.keymap();
        assertThat(resolution.problems()).isEmpty();
        assertThat(keymap.actionFor(KeyEvent.VK_D, CTRL_SHIFT)).contains(new LayoutAction.Split(Orientation.ROW));
        assertThat(keymap.actionFor(KeyEvent.VK_EQUALS, ALT_SHIFT)).isEmpty();
        assertThat(keymap.actionFor(KeyEvent.VK_PLUS, ALT_SHIFT)).isEmpty();
        assertThat(keymap.actionFor(KeyEvent.VK_ADD, ALT_SHIFT)).isEmpty();
        assertThat(keymap.keyOf(new LayoutAction.Split(Orientation.ROW))).contains("Ctrl+Shift+D");
    }

    @Test
    @DisplayName("다른 동작의 기본 키로 바꾸면 재정의가 이기고, 키를 잃은 동작은 키가 없으며 Unbound 로 알린다")
    void overrideWinsOverDefault() {
        // when
        KeymapResolution resolution = resolve(Map.of("pane.zoom", "alt LEFT"));

        // then
        Keymap keymap = resolution.keymap();
        assertThat(keymap.actionFor(KeyEvent.VK_LEFT, ALT_DOWN_MASK)).contains(new LayoutAction.Zoom());
        assertThat(keymap.keyOf(new LayoutAction.Focus(Direction.LEFT))).isEmpty();
        assertThat(keymap.actionFor(KeyEvent.VK_Z, CTRL_SHIFT)).isEmpty();
        assertThat(resolution.problems())
                .containsExactly(new KeyProblem.Unbound("pane.focusLeft", "Alt+←", "pane.zoom"));
    }

    @Test
    @DisplayName("사이드바 토글 키(Ctrl+Shift+E)를 가져가도 같은 규칙 — 재정의가 이기고 Unbound")
    void overrideTakesSidebarKey() {
        // when
        KeymapResolution resolution = resolve(Map.of("pane.close", "ctrl shift E"));

        // then
        Keymap keymap = resolution.keymap();
        assertThat(keymap.actionFor(KeyEvent.VK_E, CTRL_SHIFT)).contains(new LayoutAction.Close());
        assertThat(keymap.keyOf(new KeyAction.ToggleSidebar())).isEmpty();
        assertThat(resolution.problems())
                .containsExactly(new KeyProblem.Unbound("sidebar.toggle", "Ctrl+Shift+E", "pane.close"));
    }

    @Test
    @DisplayName("두 동작을 같은 키로 바꾸면 둘 다 기본값이고 Conflict 로 알린다")
    void conflictRevertsBoth() {
        // when
        KeymapResolution resolution = resolve(Map.of("pane.zoom", "ctrl shift D", "pane.close", "ctrl shift D"));

        // then
        Keymap keymap = resolution.keymap();
        assertThat(keymap.actionFor(KeyEvent.VK_D, CTRL_SHIFT)).isEmpty();
        assertThat(keymap.actionFor(KeyEvent.VK_Z, CTRL_SHIFT)).contains(new LayoutAction.Zoom());
        assertThat(keymap.actionFor(KeyEvent.VK_W, CTRL_SHIFT)).contains(new LayoutAction.Close());
        assertThat(resolution.problems())
                .containsExactly(new KeyProblem.Conflict("Ctrl+Shift+D", List.of("pane.close", "pane.zoom")));
    }

    @Test
    @DisplayName("겹쳐 기본값으로 돌아간 동작의 키를 다른 재정의가 가져가면 그 동작은 Unbound 다(다시 검사)")
    void conflictThenRecheck() {
        // given 줌 · 닫기가 겹쳐 기본값으로 돌아가는데, 교환이 닫기의 기본 키를 가져간다
        Map<String, String> bindings = Map.of(
                "pane.zoom", "ctrl shift D",
                "pane.close", "ctrl shift D",
                "pane.swapLeft", "ctrl shift W");

        // when
        KeymapResolution resolution = resolve(bindings);

        // then
        Keymap keymap = resolution.keymap();
        assertThat(keymap.actionFor(KeyEvent.VK_W, CTRL_SHIFT)).contains(new LayoutAction.Swap(Direction.LEFT));
        assertThat(keymap.keyOf(new LayoutAction.Close())).isEmpty();
        assertThat(keymap.actionFor(KeyEvent.VK_Z, CTRL_SHIFT)).contains(new LayoutAction.Zoom());
        assertThat(resolution.problems())
                .containsExactly(
                        new KeyProblem.Conflict("Ctrl+Shift+D", List.of("pane.close", "pane.zoom")),
                        new KeyProblem.Unbound("pane.close", "Ctrl+Shift+W", "pane.swapLeft"));
    }

    @Test
    @DisplayName("두 동작의 키를 서로 바꾸면 둘 다 바뀐 키로 동작하고 경고가 없다")
    void swapKeys() {
        // when
        KeymapResolution resolution = resolve(Map.of("pane.zoom", "ctrl shift W", "pane.close", "ctrl shift Z"));

        // then
        Keymap keymap = resolution.keymap();
        assertThat(resolution.problems()).isEmpty();
        assertThat(keymap.actionFor(KeyEvent.VK_W, CTRL_SHIFT)).contains(new LayoutAction.Zoom());
        assertThat(keymap.actionFor(KeyEvent.VK_Z, CTRL_SHIFT)).contains(new LayoutAction.Close());
    }

    @Test
    @DisplayName("Ctrl+C 로 바꾸면 Reserved 로 알리고 기본 키를 쓴다")
    void reservedShellKey() {
        // when
        KeymapResolution resolution = resolve(Map.of("pane.zoom", "ctrl C"));

        // then
        Keymap keymap = resolution.keymap();
        assertThat(resolution.problems()).containsExactly(new KeyProblem.Reserved("pane.zoom", "Ctrl+C"));
        assertThat(keymap.actionFor(KeyEvent.VK_C, CTRL_DOWN_MASK)).isEmpty();
        assertThat(keymap.actionFor(KeyEvent.VK_Z, CTRL_SHIFT)).contains(new LayoutAction.Zoom());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(
            strings = {
                "ctrl D",
                "ctrl Z",
                "ctrl R",
                "ctrl L",
                "ctrl A",
                "ctrl E",
                "ctrl W",
                "ctrl U",
                "ctrl K",
                "TAB",
                "ENTER",
                "ESCAPE",
                "A",
                "shift A",
                "SPACE",
                "LEFT",
                // 터미널의 복사 · 붙여넣기
                "ctrl shift C",
                "ctrl shift V",
                "ctrl INSERT",
                "shift INSERT"
            })
    @DisplayName("셸 · 에디터 · 터미널이 쓰는 키와 수식키 없는 글자는 예약 키다")
    void reservedKeys(String raw) {
        // when
        KeymapResolution resolution = resolve(Map.of("pane.zoom", raw));

        // then
        assertThat(resolution.problems()).singleElement().isInstanceOf(KeyProblem.Reserved.class);
        assertThat(resolution.keymap().keyOf(new LayoutAction.Zoom())).contains("Ctrl+Shift+Z");
    }

    @Test
    @DisplayName("수식키 없는 기능 키(F5)는 예약 키가 아니다")
    void functionKeyAllowed() {
        // when
        KeymapResolution resolution = resolve(Map.of("pane.zoom", "F5"));

        // then
        assertThat(resolution.problems()).isEmpty();
        assertThat(resolution.keymap().actionFor(KeyEvent.VK_F5, 0)).contains(new LayoutAction.Zoom());
    }

    @Test
    @DisplayName("읽을 수 없는 원문은 Unparseable 로 알리고 기본 키를 쓴다")
    void unparseable() {
        // when
        KeymapResolution resolution = resolve(Map.of("pane.zoom", "foo bar"));

        // then
        assertThat(resolution.problems()).containsExactly(new KeyProblem.Unparseable("pane.zoom", "foo bar"));
        assertThat(resolution.keymap().keyOf(new LayoutAction.Zoom())).contains("Ctrl+Shift+Z");
    }

    @Test
    @DisplayName("모르는 동작 id 는 UnknownAction 으로 알리고 무시한다")
    void unknownAction() {
        // when
        KeymapResolution resolution = resolve(Map.of("pane.explode", "ctrl shift D"));

        // then
        assertThat(resolution.problems()).containsExactly(new KeyProblem.UnknownAction("pane.explode"));
        assertThat(resolution.keymap().actionFor(KeyEvent.VK_D, CTRL_SHIFT)).isEmpty();
    }

    @Test
    @DisplayName("Ctrl+Alt 조합은 적용하되 AltGr 와 겹친다고 경고만 한다")
    void altGraphWarning() {
        // when
        KeymapResolution resolution = resolve(Map.of("pane.zoom", "ctrl alt Z"));

        // then
        assertThat(resolution.problems()).containsExactly(new KeyProblem.AltGraph("pane.zoom", "Ctrl+Alt+Z"));
        assertThat(resolution.keymap().actionFor(KeyEvent.VK_Z, CTRL_DOWN_MASK | ALT_DOWN_MASK))
                .contains(new LayoutAction.Zoom());
    }

    @Test
    @DisplayName("prefix 를 적으면 아직 지원하지 않는다고 알리고 무시한다(FR-LAY-08 은 후속 이슈)")
    void prefixNotSupported() {
        // when
        KeymapResolution resolution = Keymap.resolve(Keymap.defaults(), new KeyConfig(Optional.of("ctrl B"), Map.of()));

        // then
        assertThat(resolution.problems()).containsExactly(new KeyProblem.PrefixNotSupported("ctrl B"));
        assertThat(resolution.keymap().bindings()).isEqualTo(Keymap.defaults().bindings());
    }

    @Test
    @DisplayName("여러 문제는 동작 id 순서로 늘 같은 순서다")
    void deterministicOrder() {
        // when
        KeymapResolution resolution = resolve(Map.of("tree.search", "foo", "pane.zoom", "ctrl C", "a.b", "F5"));

        // then
        assertThat(resolution.problems())
                .containsExactly(
                        new KeyProblem.UnknownAction("a.b"),
                        new KeyProblem.Reserved("pane.zoom", "Ctrl+C"),
                        new KeyProblem.Unparseable("tree.search", "foo"));
    }
}
