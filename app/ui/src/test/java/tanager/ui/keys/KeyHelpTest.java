package tanager.ui.keys;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.layout.Direction;

class KeyHelpTest {

    @Test
    @DisplayName("도움말은 표의 동작마다 한 줄 — 방향 키는 한 줄로 묶는다(테스트 언어는 한국어)")
    void rows() {
        // when
        List<KeyHelp.Row> rows = KeyHelp.rows();

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
    @DisplayName("툴팁은 동작과 키를 괄호로 잇는다")
    void withKey() {
        // when
        String text = KeyHelp.withKey("줌", new LayoutAction.Zoom());

        // then
        assertThat(text).isEqualTo("줌 (Ctrl+Shift+Z)");
    }

    @Test
    @DisplayName("키가 없는 동작의 툴팁은 이름만")
    void withoutKey() {
        // when
        String text = KeyHelp.withKey("위로 교환", new LayoutAction.Swap(Direction.UP));

        // then
        assertThat(text).isEqualTo("위로 교환");
    }
}
