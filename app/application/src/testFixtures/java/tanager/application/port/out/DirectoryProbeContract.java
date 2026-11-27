package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tanager.domain.history.WorkspacePath;

/** {@link DirectoryProbe} 계약(#53). Fake 와 플랫폼 구현이 같이 상속한다. 하위 클래스는 결정적인 구성을 돌려준다. */
@Tag("contract")
public abstract class DirectoryProbeContract {
    /** 계약 검사에 쓰는 넉넉한 시간 제한. 결정적인 구성이면 기다리지 않고 답한다. */
    protected static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 검사할 확인기. */
    protected abstract DirectoryProbe probe();

    /** {@link #probe()} 가 있는 디렉터리로 봐야 하는 경로. */
    protected abstract WorkspacePath existingDirectory();

    /** {@link #probe()} 가 없다고 봐야 하는 경로. */
    protected abstract WorkspacePath missingPath();

    @Test
    @DisplayName("있는 디렉터리는 Optional.of(true) 다")
    void existingDirectoryIsTrue() {
        // when
        var answer = probe().isDirectory(existingDirectory(), TIMEOUT);

        // then
        assertThat(answer).contains(true);
    }

    @Test
    @DisplayName("없는 경로는 Optional.of(false) 다 — 모름(empty)과 구분한다")
    void missingPathIsFalse() {
        // when
        var answer = probe().isDirectory(missingPath(), TIMEOUT);

        // then
        assertThat(answer).contains(false);
    }
}
