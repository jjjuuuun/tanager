package tanager.application.port.out;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import tanager.domain.shared.Result;

/**
 * {@link PtyLauncher} 의 수작업 Fake. 받은 요청을 기록하고, 미리 정한 대로 성공({@link ScriptedTerminalStream})
 * 또는 실패를 돌려준다. 스레드 안전.
 */
public final class ScriptedPtyLauncher implements PtyLauncher {
    private final List<LaunchCommand> commands = new ArrayList<>();
    private final List<TerminalSize> sizes = new ArrayList<>();
    private final List<ScriptedTerminalStream> streams = new ArrayList<>();
    private final List<FailureRule> rules = new ArrayList<>();
    private Optional<LaunchError> failure = Optional.empty();
    private Consumer<ScriptedTerminalStream> setup = stream -> {};
    private long nextPid = 1000;

    /** 앞으로의 {@code launch} 가 이 오류로 실패한다. */
    public synchronized ScriptedPtyLauncher willFail(LaunchError error) {
        this.failure = Optional.of(Objects.requireNonNull(error));
        return this;
    }

    /** 조건에 맞는 명령의 {@code launch} 만 이 오류로 실패한다. 규칙은 쌓이고 먼저 등록한 것이 먼저 적용되며 {@link #willFail} 보다 앞선다. */
    public synchronized ScriptedPtyLauncher willFailFor(Predicate<LaunchCommand> matches, LaunchError error) {
        rules.add(new FailureRule(Objects.requireNonNull(matches), Objects.requireNonNull(error)));
        return this;
    }

    /** 앞으로 만들어 줄 스트림을 꾸미는 시나리오(예: {@code s.whenStarted(...)}, {@code s.echoWrites()}). */
    public synchronized ScriptedPtyLauncher willScript(Consumer<ScriptedTerminalStream> setup) {
        this.failure = Optional.empty();
        this.setup = Objects.requireNonNull(setup);
        return this;
    }

    @Override
    public synchronized Result<TerminalStream, LaunchError> launch(LaunchCommand command, TerminalSize initialSize) {
        commands.add(command);
        sizes.add(initialSize);
        for (FailureRule rule : rules) {
            if (rule.matches().test(command)) {
                return Result.err(rule.error());
            }
        }
        if (failure.isPresent()) {
            return Result.err(failure.get());
        }
        ScriptedTerminalStream stream = new ScriptedTerminalStream(nextPid++);
        setup.accept(stream);
        streams.add(stream);
        return Result.ok(stream);
    }

    /** {@code launch} 에 넘어온 명령들(불변 사본). */
    public synchronized List<LaunchCommand> commands() {
        return List.copyOf(commands);
    }

    /** {@code launch} 에 넘어온 초기 크기들(불변 사본). */
    public synchronized List<TerminalSize> sizes() {
        return List.copyOf(sizes);
    }

    /** 돌려준 스트림들(불변 사본). */
    public synchronized List<ScriptedTerminalStream> streams() {
        return List.copyOf(streams);
    }

    private record FailureRule(Predicate<LaunchCommand> matches, LaunchError error) {}
}
