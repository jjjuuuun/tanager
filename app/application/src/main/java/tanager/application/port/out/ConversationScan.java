package tanager.application.port.out;

import java.util.List;
import java.util.Objects;
import tanager.domain.history.PastConversation;

/**
 * {@link ConversationSource#scan} 의 결과. {@code skippedFiles} 는 읽지 못했거나 형식이 틀려 건너뛴 기록 파일 수다(로그 · 경고용). 불변식:
 * skippedFiles ≥ 0.
 */
public record ConversationScan(List<PastConversation> conversations, int skippedFiles) {

    public ConversationScan {
        conversations = List.copyOf(Objects.requireNonNull(conversations, "conversations"));
        if (skippedFiles < 0) {
            throw new IllegalArgumentException("건너뛴 파일 수는 음수일 수 없다: " + skippedFiles);
        }
    }
}
