package dev.tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // when
            Future<List<ShellProfile>> first = pool.submit(catalog::list);
            Future<List<ShellProfile>> second = pool.submit(catalog::list);
            await().atMost(Duration.ofSeconds(10)).until(() -> platform.shellsCalls() >= 1);
            release.countDown();

            // then
            assertThat(first.get()).containsExactly(CMD);
            assertThat(second.get()).containsExactly(CMD);
            assertThat(platform.shellsCalls()).isEqualTo(1);
        }
    }
}
