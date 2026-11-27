package tanager.platform.windows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 명령마다 정해 둔 결과를 돌려주고 받은 명령을 기록하는 수작업 Fake. 정하지 않은 명령은 {@code NotStarted}. */
final class ScriptedCommandRunner implements CommandRunner {
    private final Map<List<String>, CommandOutcome> outcomes = new HashMap<>();
    private final List<List<String>> commands = new ArrayList<>();
    private final List<Duration> timeouts = new ArrayList<>();

    /** {@code command} 를 받으면 {@code outcome} 을 돌려준다. */
    ScriptedCommandRunner on(List<String> command, CommandOutcome outcome) {
        outcomes.put(List.copyOf(command), Objects.requireNonNull(outcome, "outcome"));
        return this;
    }

    @Override
    public CommandOutcome run(List<String> command, Map<String, String> env, Duration timeout) {
        commands.add(List.copyOf(command));
        timeouts.add(timeout);
        return outcomes.getOrDefault(List.copyOf(command), new CommandOutcome.NotStarted("정하지 않은 명령"));
    }

    /** 받은 명령들(받은 순서). */
    List<List<String>> commands() {
        return List.copyOf(commands);
    }

    /** 받은 시간 초과들(받은 순서). */
    List<Duration> timeouts() {
        return List.copyOf(timeouts);
    }
}
