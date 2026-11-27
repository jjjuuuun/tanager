package tanager.platform.windows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 받은 명령과 시간 초과를 기록하고 정해 둔 결과를 돌려주는 수작업 Fake. */
final class FakeCommandRunner implements CommandRunner {
    private final CommandOutcome outcome;
    private final List<List<String>> commands = new ArrayList<>();
    private final List<Duration> timeouts = new ArrayList<>();

    FakeCommandRunner(CommandOutcome outcome) {
        this.outcome = outcome;
    }

    @Override
    public CommandOutcome run(List<String> command, Duration timeout) {
        commands.add(List.copyOf(command));
        timeouts.add(timeout);
        return outcome;
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
