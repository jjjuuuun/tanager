package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PastConversationTest {

    private static final ConversationId ID = new ConversationId("c1");
    private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");

    private static WorkspacePath cwd() {
        return WorkspacePath.of(new ShellEnvironment.Wsl("Ubuntu"), "/home/me/work")
                .value()
                .orElseThrow();
    }

    @Test
    @DisplayName("요약은 줄바꿈 · 탭 · 연속 공백을 공백 하나로 접고 앞뒤 공백을 벗긴다")
    void summarizeCollapsesWhitespace() {
        // given
        String raw = "  첫 줄\n\n둘째\t\t줄   끝  ";
        // when
        Optional<String> title = PastConversation.summarize(raw);
        // then
        assertThat(title).contains("첫 줄 둘째 줄 끝");
    }

    @Test
    @DisplayName("요약은 80 코드 포인트(한글 포함)로 자른다")
    void summarizeTruncatesTo80CodePoints() {
        // given 300자 — 한글과 영문, 줄바꿈 · 탭이 섞였다
        String raw = ("가나다라마바사아자차\n" + "abcdefghi\t").repeat(15);
        // when
        String title = PastConversation.summarize(raw).orElseThrow();
        // then
        assertThat(title.codePointCount(0, title.length())).isEqualTo(80);
        assertThat(title).startsWith("가나다라마바사아자차 abcdefghi 가나다").doesNotContain("\n", "\t");
    }

    @Test
    @DisplayName("보조 평면 문자를 반으로 자르지 않는다")
    void summarizeKeepsSurrogatePairs() {
        // given
        String smile = "😀";
        // when
        String title = PastConversation.summarize(smile.repeat(100)).orElseThrow();
        // then
        assertThat(title).isEqualTo(smile.repeat(80));
    }

    @Test
    @DisplayName("자른 끝에 남은 공백은 벗긴다")
    void summarizeStripsAfterTruncation() {
        // given 80번째 코드 포인트가 공백이다
        String raw = "a".repeat(79) + " " + "b".repeat(10);
        // when / then
        assertThat(PastConversation.summarize(raw)).contains("a".repeat(79));
    }

    @Test
    @DisplayName("제어 문자 · 방향 제어 문자는 지운다 — 트리 표시가 깨지거나 뒤집히지 않게")
    void summarizeDropsControlAndFormatCharacters() {
        // given
        String raw = "a\u001b[31mb‮c\u0000d";
        // when / then
        assertThat(PastConversation.summarize(raw)).contains("a[31mbcd");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n\t\r", "​‮"})
    @DisplayName("보이는 글자가 없으면 empty")
    void summarizeBlankIsEmpty(String raw) {
        // given / when / then
        assertThat(PastConversation.summarize(raw)).isEmpty();
    }

    @Test
    @DisplayName("요약 규칙에 맞는 제목과 함께 만든다")
    void createsWithTitle() {
        // given / when
        var conversation = new PastConversation(VisitSource.CLAUDE_CODE, ID, cwd(), Optional.of("버그 고치기"), AT);
        // then
        assertThat(conversation.title()).contains("버그 고치기");
        assertThat(conversation.source()).isEqualTo(VisitSource.CLAUDE_CODE);
        assertThat(conversation.cwd()).isEqualTo(cwd());
        assertThat(conversation.lastActive()).isEqualTo(AT);
    }

    @Test
    @DisplayName("제목이 없는 대화도 만든다")
    void createsWithoutTitle() {
        // given / when
        var conversation = new PastConversation(VisitSource.CLAUDE_CODE, ID, cwd(), Optional.empty(), AT);
        // then
        assertThat(conversation.title()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " 앞 공백", "줄\n바꿈", "두  칸", "‮뒤집기"})
    @DisplayName("요약 규칙에 맞지 않는 제목은 거절한다")
    void rejectsUnnormalizedTitle(String title) {
        // given / when / then
        assertThatThrownBy(() -> new PastConversation(VisitSource.CLAUDE_CODE, ID, cwd(), Optional.of(title), AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("80 코드 포인트를 넘는 제목은 거절한다")
    void rejectsTooLongTitle() {
        // given
        Optional<String> title = Optional.of("가".repeat(81));
        // when / then
        assertThatThrownBy(() -> new PastConversation(VisitSource.CLAUDE_CODE, ID, cwd(), title, AT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
