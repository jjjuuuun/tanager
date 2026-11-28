package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ConversationId;
import tanager.domain.history.PastConversation;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.history.WorkspacePath;

class ConversationScanTest {

    private static PastConversation conversation(String id) {
        WorkspacePath cwd = WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work")
                .value()
                .orElseThrow();
        return new PastConversation(
                VisitSource.CLAUDE_CODE, new ConversationId(id), cwd, Optional.empty(), Instant.EPOCH);
    }

    @Test
    @DisplayName("대화 목록을 방어 복사한다")
    void copiesConversations() {
        // given
        List<PastConversation> source = new ArrayList<>(List.of(conversation("a")));
        // when
        var scan = new ConversationScan(source, 2);
        source.add(conversation("b"));
        // then
        assertThat(scan.conversations()).containsExactly(conversation("a"));
        assertThat(scan.skippedFiles()).isEqualTo(2);
    }

    @Test
    @DisplayName("건너뛴 파일 수는 음수일 수 없다")
    void rejectsNegativeSkipped() {
        // given / when / then
        assertThatThrownBy(() -> new ConversationScan(List.of(), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("오류마다 개발자용 설명이 있다(로그)")
    void errorsDescribeThemselves() {
        // given
        List<ConversationError> errors = List.of(
                new ConversationError.NoHistory(),
                new ConversationError.UnknownFormat(),
                new ConversationError.Unreadable());
        // when / then
        assertThat(errors)
                .extracting(ConversationError::describe)
                .allSatisfy(d -> assertThat(d).isNotBlank());
    }
}
