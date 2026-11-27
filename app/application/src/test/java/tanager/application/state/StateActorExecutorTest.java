package tanager.application.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StateActorExecutorTest {

    @Test
    @DisplayName("운영 실행기는 tanager-state 라는 이름의 플랫폼 데몬 스레드 하나에서 돈다")
    void serialExecutorUsesNamedPlatformDaemonThread() throws Exception {
        // given
        ExecutorService serial = StateActor.serialExecutor();
        try {
            // when
            Thread first =
                    CompletableFuture.supplyAsync(Thread::currentThread, serial).get(5, TimeUnit.SECONDS);
            Thread second =
                    CompletableFuture.supplyAsync(Thread::currentThread, serial).get(5, TimeUnit.SECONDS);

            // then
            assertThat(first.getName()).isEqualTo("tanager-state");
            assertThat(first.isDaemon()).isTrue();
            assertThat(first.isVirtual()).isFalse();
            assertThat(second).isSameAs(first);
        } finally {
            serial.shutdownNow();
        }
    }
}
