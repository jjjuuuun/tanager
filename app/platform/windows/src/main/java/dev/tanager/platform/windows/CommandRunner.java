package dev.tanager.platform.windows;

import java.time.Duration;
import java.util.List;

/** 외부 명령을 한 번 실행하고 결과를 값으로 돌려준다. 예외를 던지지 않는다. */
interface CommandRunner {

    /** {@code command} 를 실행하고 {@code timeout} 안에 끝나지 않으면 강제 종료한다. 블로킹. */
    CommandOutcome run(List<String> command, Duration timeout);
}
