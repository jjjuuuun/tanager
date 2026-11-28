package tanager.application.state;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.application.port.in.AppEvent;

/**
 * 그 origin 의 대화를 이번 실행 동안 가져올 수 없게 됐다(형식을 모름 · 읽을 수 없음, ADR-0020, #215). 그 목록을 지우고(바뀌었으면
 * {@link AppEvent.ConversationsChanged}) {@link AppEvent.ImportNotice} 를 낸다. 이력은 그대로다. 응답은 언제나 {@code true}.
 */
public record ConversationsUnavailable(AppEvent.ImportProblem problem) implements Command<Boolean> {

    public ConversationsUnavailable {
        Objects.requireNonNull(problem, "problem");
    }

    @Override
    public Transition<Boolean> apply(AppState current) {
        return ConversationsCleared.without(
                current, Optional.of(problem.origin()), List.of(new AppEvent.ImportNotice(problem)));
    }
}
