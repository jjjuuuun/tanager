package tanager.application.port.out;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tanager.domain.history.ConversationId;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.session.ShellLine;
import tanager.domain.shared.Result;

/**
 * {@link ConversationSource} 의 수작업 Fake(#215). 스레드 안전. 환경마다 {@link #answer} 로 정한 결과를 돌려주고, 정하지 않은 환경은
 * {@link ConversationError.NoHistory} 다. 받은 호출을 {@link #scans()} 에 기록한다.
 */
public final class ScriptedConversationSource implements ConversationSource {
    private final VisitSource source;
    private final Map<ShellEnvironment, Result<ConversationScan, ConversationError>> answers = new HashMap<>();
    private final List<Scan> scans = new ArrayList<>();

    /** 받은 호출 하나. */
    public record Scan(ShellEnvironment env, Path hostHome) {}

    public ScriptedConversationSource(VisitSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** {@code env} 를 읽으면 {@code result} 를 돌려준다. */
    public synchronized ScriptedConversationSource answer(
            ShellEnvironment env, Result<ConversationScan, ConversationError> result) {
        answers.put(Objects.requireNonNull(env, "env"), Objects.requireNonNull(result, "result"));
        return this;
    }

    @Override
    public VisitSource source() {
        return source;
    }

    @Override
    public boolean supports(ShellEnvironment env) {
        return true;
    }

    @Override
    public synchronized Result<ConversationScan, ConversationError> scan(ShellEnvironment env, Path hostHome) {
        scans.add(new Scan(Objects.requireNonNull(env, "env"), Objects.requireNonNull(hostHome, "hostHome")));
        return answers.getOrDefault(env, Result.err(new ConversationError.NoHistory()));
    }

    @Override
    public ShellLine resume(ConversationId id) {
        return new ShellLine("resume " + id.value());
    }

    @Override
    public ShellLine continueLatest() {
        return new ShellLine("continue");
    }

    /** 받은 호출들(받은 순서, 불변 사본). */
    public synchronized List<Scan> scans() {
        return List.copyOf(scans);
    }
}
