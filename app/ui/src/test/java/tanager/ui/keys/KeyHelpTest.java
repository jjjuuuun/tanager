package tanager.ui.keys;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.KeyConfig;
import tanager.domain.layout.Direction;

class KeyHelpTest {

    private static Keymap overridden(Map<String, String> bindings) {
        return Keymap.resolve(Keymap.defaults(), new KeyConfig(Optional.empty(), bindings))
                .keymap();
    }

    @Test
    @DisplayName("도움말은 표의 동작마다 한 줄 — 방향 키는 한 줄로 묶는다(테스트 언어는 한국어)")
    void rows() {
        // when
        List<KeyHelp.Row> rows = KeyHelp.rows(Keymap.defaults());

        // then
        assertThat(rows)
                .containsExactly(
                        new KeyHelp.Row("오른쪽으로 분할", "Alt+Shift+="),
                        new KeyHelp.Row("아래로 분할", "Alt+Shift+-"),
                        new KeyHelp.Row("포커스 이동", "Alt+←↑→↓"),
                        new KeyHelp.Row("크기 조절(5%)", "Alt+Shift+←↑→↓"),
                        new KeyHelp.Row("줌 토글", "Ctrl+Shift+Z"),
                        new KeyHelp.Row("창 닫기", "Ctrl+Shift+W"),
                        new KeyHelp.Row("창 교환", "Ctrl+Shift+←→"));
    }

    @Test
    @DisplayName("레이아웃 밖의 키(사이드바 · 트리 검색 · 설정 열기)도 한 줄씩 보인다")
    void appRows() {
        // when
        List<KeyHelp.Row> rows = KeyHelp.appRows(Keymap.defaults());

        // then
        assertThat(rows)
                .containsExactly(
                        new KeyHelp.Row("사이드바 보이기 · 숨기기", "Ctrl+Shift+E"),
                        new KeyHelp.Row("트리 검색", "Ctrl+Shift+F"),
                        new KeyHelp.Row("설정 열기", "Ctrl+,"));
    }

    @Test
    @DisplayName("도움말은 실제로 쓰는 키를 보인다 — 재정의한 키, 묶을 수 없는 방향 키는 따로, 키를 잃은 동작은 —")
    void rowsFollowOverrides() {
        // given 줌이 포커스 왼쪽 키를 가져가고, 오른쪽 분할을 바꾼다
        Keymap keymap = overridden(Map.of("pane.zoom", "alt LEFT", "pane.splitRight", "ctrl shift D"));

        // when
        List<KeyHelp.Row> rows = KeyHelp.rows(keymap);

        // then
        assertThat(rows)
                .contains(
                        new KeyHelp.Row("오른쪽으로 분할", "Ctrl+Shift+D"),
                        new KeyHelp.Row("포커스 이동", "Alt+↑ · Alt+→ · Alt+↓"),
                        new KeyHelp.Row("줌 토글", "Alt+←"));
    }

    @Test
    @DisplayName("키를 모두 잃은 동작은 — 로 보인다")
    void unboundRow() {
        // given
        Keymap keymap = overridden(Map.of("pane.zoom", "ctrl shift W"));

        // when
        List<KeyHelp.Row> rows = KeyHelp.rows(keymap);

        // then
        assertThat(rows).contains(new KeyHelp.Row("창 닫기", "—"), new KeyHelp.Row("줌 토글", "Ctrl+Shift+W"));
    }

    @Test
    @DisplayName("툴팁은 동작과 키를 괄호로 잇는다")
    void withKey() {
        // when
        String text = KeyHelp.withKey(Keymap.defaults(), "줌", new LayoutAction.Zoom());

        // then
        assertThat(text).isEqualTo("줌 (Ctrl+Shift+Z)");
    }

    @Test
    @DisplayName("툴팁도 재정의한 키를 보인다")
    void withOverriddenKey() {
        // given
        Keymap keymap = overridden(Map.of("pane.zoom", "ctrl shift D"));

        // when
        String text = KeyHelp.withKey(keymap, "줌", new LayoutAction.Zoom());

        // then
        assertThat(text).isEqualTo("줌 (Ctrl+Shift+D)");
    }

    @Test
    @DisplayName("키가 없는 동작의 툴팁은 이름만")
    void withoutKey() {
        // when
        String text = KeyHelp.withKey(Keymap.defaults(), "위로 교환", new LayoutAction.Swap(Direction.UP));

        // then
        assertThat(text).isEqualTo("위로 교환");
    }
}
