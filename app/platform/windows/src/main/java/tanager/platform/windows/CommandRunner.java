package tanager.platform.windows;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 외부 명령을 한 번 실행하고 결과를 값으로 돌려준다. 예외를 던지지 않는다. */
interface CommandRunner {

    /**
     * {@code command} 를 실행하고 {@code timeout} 안에 끝나지 않으면 강제 종료한다. 블로킹. {@code env} 는 상속한 환경 변수에 더하거나
     * 덮는다.
     */
    CommandOutcome run(List<String> command, Map<String, String> env, Duration timeout);

    /** 환경 변수를 더하지 않고 실행한다. */
    default CommandOutcome run(List<String> command, Duration timeout) {
        return run(command, Map.of(), timeout);
    }
}
