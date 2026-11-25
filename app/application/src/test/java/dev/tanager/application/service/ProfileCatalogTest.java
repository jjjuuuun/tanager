package dev.tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.tanager.application.port.out.FakePlatformModule;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProfileCatalogTest {
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);

    @Test
    @DisplayName("list 는 플랫폼이 찾은 셸을 돌려준다")
    void listReturnsPlatformShells() {
        // given
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD));

        // when
        List<ShellProfile> shells = new ProfileCatalog(platform).list();

        // then
        assertThat(shells).containsExactly(CMD);
    }

    @Test
    @DisplayName("list 를 여러 번 불러도 셸 탐지는 한 번만 한다")
    void detectsOnlyOnce() {
        // given
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD));
        ProfileCatalog catalog = new ProfileCatalog(platform);

        // when
        catalog.list();
        catalog.list();

        // then
        assertThat(platform.shellsCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("두 스레드가 동시에 list 를 불러도 셸 탐지는 한 번만 한다")
    void concurrentCallsDetectOnlyOnce() throws Exception {
        // given
        CountDownLatch release = new CountDownLatch(1);
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).blockShellsUntil(release);
        ProfileCatalog catalog = new ProfileCatalog(platform);
        CompletableFuture<List<ShellProfile>> first = new CompletableFuture<>();
        CompletableFuture<List<ShellProfile>> second = new CompletableFuture<>();
        Thread detecting = Thread.ofPlatform().start(() -> first.complete(catalog.list()));
        await().atMost(Duration.ofSeconds(10)).until(() -> platform.shellsCalls() >= 1);

        // when — 두 번째 호출이 탐지를 기다리는 상태가 된 뒤에 탐지를 끝낸다
        Thread waiting = Thread.ofPlatform().start(() -> second.complete(catalog.list()));
        await().atMost(Duration.ofSeconds(10)).until(() -> isParked(waiting));
        release.countDown();
        detecting.join(Duration.ofSeconds(10));
        waiting.join(Duration.ofSeconds(10));

        // then
        assertThat(first).isCompletedWithValue(List.of(CMD));
        assertThat(second).isCompletedWithValue(List.of(CMD));
        assertThat(platform.shellsCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("셸 탐지가 예외로 끝나면 그 뒤의 list 도 다시 탐지하지 않고 IllegalStateException 이다")
    void detectionFailureIsCached() {
        // given
        FakePlatformModule platform =
                new FakePlatformModule(List.of(CMD)).failShellsWith(new IllegalArgumentException("탐지 실패"));
        ProfileCatalog catalog = new ProfileCatalog(platform);

        // when / then
        assertThatThrownBy(catalog::list).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(catalog::list).isInstanceOf(IllegalStateException.class);
        assertThat(platform.shellsCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("탐지를 기다리던 스레드가 인터럽트되어도 결과를 받고 인터럽트 표시는 남는다")
    void interruptedWaiterStillGetsResultAndKeepsFlag() throws Exception {
        // given
        CountDownLatch release = new CountDownLatch(1);
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).blockShellsUntil(release);
        ProfileCatalog catalog = new ProfileCatalog(platform);
        Thread detecting = Thread.ofPlatform().start(catalog::list);
        await().atMost(Duration.ofSeconds(10)).until(() -> platform.shellsCalls() >= 1);
        CompletableFuture<List<ShellProfile>> result = new CompletableFuture<>();
        AtomicBoolean interruptedAfter = new AtomicBoolean();
        Thread waiting = Thread.ofPlatform().start(() -> {
            result.complete(catalog.list());
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });
        await().atMost(Duration.ofSeconds(10)).until(() -> isParked(waiting));

        // when
        waiting.interrupt();
        release.countDown();
        waiting.join(Duration.ofSeconds(10));
        detecting.join(Duration.ofSeconds(10));

        // then
        assertThat(result).isCompletedWithValue(List.of(CMD));
        assertThat(interruptedAfter).isTrue();
    }

    @Test
    @DisplayName("이미 인터럽트된 스레드가 불러도 결과를 받고 인터럽트 표시는 남는다")
    void alreadyInterruptedCallerKeepsFlag() {
        // given
        ProfileCatalog catalog = new ProfileCatalog(new FakePlatformModule(List.of(CMD)));
        Thread.currentThread().interrupt();
        try {
            // when
            List<ShellProfile> shells = catalog.list();

            // then
            assertThat(shells).containsExactly(CMD);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static boolean isParked(Thread thread) {
        Thread.State state = thread.getState();
        return state == Thread.State.WAITING || state == Thread.State.BLOCKED;
    }
}
