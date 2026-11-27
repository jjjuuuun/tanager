package tanager.application.port.out;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.history.WorkspacePath;

/**
 * {@link DirectoryProbe} 의 수작업 Fake. 정하지 않은 경로는 있다(true)고 답한다. {@link #missing} 은 false, {@link #unknown} 은
 * empty. 받은 경로와 시간 제한을 기록한다. 스레드 안전.
 */
public final class FakeDirectoryProbe implements DirectoryProbe {
    private final Map<WorkspacePath, Optional<Boolean>> answers = new HashMap<>();
    private final List<Call> calls = new ArrayList<>();

    /** {@code isDirectory} 에 넘어온 인자 하나. */
    public record Call(WorkspacePath path, Duration timeout) {}

    /** 이 경로들은 없다(false). */
    public synchronized FakeDirectoryProbe missing(WorkspacePath... paths) {
        return answer(Optional.of(false), paths);
    }

    /** 이 경로들은 모른다(empty — 시간 초과 · 접근 불가 흉내). */
    public synchronized FakeDirectoryProbe unknown(WorkspacePath... paths) {
        return answer(Optional.empty(), paths);
    }

    private FakeDirectoryProbe answer(Optional<Boolean> answer, WorkspacePath... paths) {
        for (WorkspacePath path : paths) {
            answers.put(Objects.requireNonNull(path, "path"), answer);
        }
        return this;
    }

    @Override
    public synchronized Optional<Boolean> isDirectory(WorkspacePath path, Duration timeout) {
        calls.add(new Call(path, timeout));
        return answers.getOrDefault(path, Optional.of(true));
    }

    /** 받은 호출(받은 순서, 불변 사본). */
    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }
}
