package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;

/** Fake 가 {@link DirectoryProbe} 계약을 지키는지, 정해 둔 답과 호출 기록이 맞는지 확인한다. */
class FakeDirectoryProbeTest extends DirectoryProbeContract {
    private static final WorkspacePath HOME = wsl("/home/me");
    private static final WorkspacePath GONE = wsl("/home/me/gone");

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(new ShellEnvironment.Wsl("Ubuntu"), raw).value().orElseThrow();
    }

    @Override
    protected DirectoryProbe probe() {
        return new FakeDirectoryProbe().missing(GONE);
    }

    @Override
    protected WorkspacePath existingDirectory() {
        return HOME;
    }

    @Override
    protected WorkspacePath missingPath() {
        return GONE;
    }

    @Test
    @DisplayName("unknown 으로 정한 경로는 모름(empty)이고, 받은 경로와 시간 제한을 기록한다")
    void unknownPathIsEmptyAndCallsAreRecorded() {
        // given
        FakeDirectoryProbe probe = new FakeDirectoryProbe().unknown(GONE);

        // when
        var answer = probe.isDirectory(GONE, Duration.ofSeconds(1));

        // then
        assertThat(answer).isEmpty();
        assertThat(probe.calls()).containsExactly(new FakeDirectoryProbe.Call(GONE, Duration.ofSeconds(1)));
    }
}
