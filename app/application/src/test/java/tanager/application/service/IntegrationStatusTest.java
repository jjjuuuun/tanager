package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.FakePlatformModule;
import tanager.application.port.out.FakeShellIntegration;
import tanager.application.port.out.Integration;
import tanager.application.port.out.ShellIntegration;

class IntegrationStatusTest {
    private static final Path DIR = Path.of("integration");

    @Test
    @DisplayName("await 는 플랫폼의 설치 결과를 돌려준다")
    void awaitReturnsInstallResult() {
        // given
        FakePlatformModule platform =
                new FakePlatformModule(List.of()).withIntegration(FakeShellIntegration.installedAt(DIR));

        // when
        Integration result = new IntegrationStatus(platform).await();

        // then
        assertThat(result).isEqualTo(new Integration.Installed(DIR));
    }

    @Test
    @DisplayName("await 를 여러 번 불러도 설치는 한 번만 한다")
    void installsOnlyOnce() {
        // given
        FakeShellIntegration integration = FakeShellIntegration.installedAt(DIR);
        IntegrationStatus status =
                new IntegrationStatus(new FakePlatformModule(List.of()).withIntegration(integration));

        // when
        status.await();
        status.await();

        // then
        assertThat(integration.installCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("설치가 예외로 끝나면 Unavailable 로 기억하고 다시 설치하지 않는다")
    void throwingInstallIsRememberedAsUnavailable() {
        // given
        FakeShellIntegration integration = FakeShellIntegration.throwing(new IllegalStateException("설치 고장"));
        IntegrationStatus status =
                new IntegrationStatus(new FakePlatformModule(List.of()).withIntegration(integration));

        // when
        Integration first = status.await();
        Integration second = status.await();

        // then
        assertThat(first).isInstanceOf(Integration.Unavailable.class);
        assertThat(((Integration.Unavailable) first).problem())
                .isEqualTo(new Integration.Problem.Crashed("IllegalStateException"));
        assertThat(second).isEqualTo(first);
        assertThat(integration.installCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("설치 중에 다른 스레드가 부르면 다시 설치하지 않고 그 결과를 기다린다")
    void concurrentCallerWaitsForRunningInstall() throws Exception {
        // given
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ShellIntegration blocking = () -> {
            calls.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Integration.Installed(DIR);
        };
        IntegrationStatus status = new IntegrationStatus(new FakePlatformModule(List.of()).withIntegration(blocking));
        CompletableFuture<Integration> first = new CompletableFuture<>();
        CompletableFuture<Integration> second = new CompletableFuture<>();
        Thread installing = Thread.ofPlatform().start(() -> first.complete(status.await()));
        await().atMost(Duration.ofSeconds(10)).until(() -> calls.get() >= 1);

        // when
        Thread waiting = Thread.ofPlatform().start(() -> second.complete(status.await()));
        await().atMost(Duration.ofSeconds(10)).until(() -> isParked(waiting));
        release.countDown();
        installing.join(Duration.ofSeconds(10));
        waiting.join(Duration.ofSeconds(10));

        // then
        assertThat(first).isCompletedWithValue(new Integration.Installed(DIR));
        assertThat(second).isCompletedWithValue(new Integration.Installed(DIR));
        assertThat(calls).hasValue(1);
    }

    private static boolean isParked(Thread thread) {
        Thread.State state = thread.getState();
        return state == Thread.State.WAITING || state == Thread.State.BLOCKED;
    }
}
