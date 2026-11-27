package tanager.ui.keys;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 키 설정 문제를 상태줄 알림 한 줄로(#183). 테스트 언어는 한국어로 고정돼 있다. */
class KeyProblemTextTest {

    @Test
    @DisplayName("문제마다 동작 id · 키를 담은 한 줄이다")
    void sentences() {
        // then
        assertThat(KeyProblemText.of(new KeyProblem.Unparseable("pane.zoom", "foo bar")))
                .isEqualTo("키 설정 pane.zoom 의 foo bar 를 읽지 못해 기본 키를 쓴다");
        assertThat(KeyProblemText.of(new KeyProblem.UnknownAction("pane.explode")))
                .isEqualTo("키 설정의 pane.explode 는 모르는 동작이라 무시했다");
        assertThat(KeyProblemText.of(new KeyProblem.Reserved("pane.zoom", "Ctrl+C")))
                .isEqualTo("키 설정 pane.zoom 의 Ctrl+C 는 셸 · 터미널이 쓰는 키라 기본 키를 쓴다");
        assertThat(KeyProblemText.of(new KeyProblem.Conflict("Ctrl+Shift+D", List.of("pane.close", "pane.zoom"))))
                .isEqualTo("키 설정에서 Ctrl+Shift+D 를 pane.close, pane.zoom 이 함께 써 모두 기본 키를 쓴다");
        assertThat(KeyProblemText.of(new KeyProblem.Unbound("pane.focusLeft", "Alt+←", "pane.zoom")))
                .isEqualTo("pane.zoom 이 Alt+← 를 가져가 pane.focusLeft 에는 키가 없다");
        assertThat(KeyProblemText.of(new KeyProblem.AltGraph("pane.zoom", "Ctrl+Alt+Z")))
                .isEqualTo("키 설정 pane.zoom 의 Ctrl+Alt+Z 는 AltGr 로 치는 글자와 겹칠 수 있다");
        assertThat(KeyProblemText.of(new KeyProblem.PrefixNotSupported("ctrl B")))
                .isEqualTo("prefix 모드(keys.prefix = ctrl B)는 아직 지원하지 않아 무시했다");
    }
}
