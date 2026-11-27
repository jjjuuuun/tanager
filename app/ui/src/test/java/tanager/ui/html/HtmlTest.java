package tanager.ui.html;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HTML 글자 만들기(#188 에서 세 곳의 같은 코드를 모았다). */
class HtmlTest {

    @Test
    @DisplayName("& < > 를 엔터티로 바꾼다")
    void escapes() {
        // when
        String escaped = Html.escape("a<b> & c");

        // then
        assertThat(escaped).isEqualTo("a&lt;b&gt; &amp; c");
    }

    @Test
    @DisplayName("줄마다 escape 해 <br> 로 잇고 <html> 로 감싼다")
    void joinsLines() {
        // when
        String html = Html.lines(List.of("C:\\<x>", "two"));

        // then
        assertThat(html).isEqualTo("<html>C:\\&lt;x&gt;<br>two</html>");
        assertThat(Html.lines(List.of())).isEqualTo("<html></html>");
    }
}
