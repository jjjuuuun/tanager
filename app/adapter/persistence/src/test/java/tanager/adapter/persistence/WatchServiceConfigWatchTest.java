package tanager.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.out.Subscription;

/** 설정 파일 감시(FR-CFG-03): 실제 파일 시스템의 WatchService 로 알림 횟수와 시간을 확인한다. */
class WatchServiceConfigWatchTest {
    // 알림이 더 오지 않는지 지켜보는 시간 — 모으는 시간(300ms)보다 넉넉히
    private static final Duration QUIET = Duration.ofMillis(800);

    @TempDir
    Path dir;

    private final AtomicInteger calls = new AtomicInteger();
    private final List<Subscription> subscriptions = new ArrayList<>();

    @AfterEach
    void cancel() {
        subscriptions.forEach(Subscription::cancel);
    }

    private Path file() {
        return dir.resolve("config.json");
    }

    private void watch(Path file, Runnable changed) {
        subscriptions.add(new WatchServiceConfigWatch(file).watch(changed));
    }

    private void watch() {
        watch(file(), calls::incrementAndGet);
    }

    private void assertCallsStay(int expected) {
        await().during(QUIET).atMost(QUIET.plusSeconds(2)).until(() -> calls.get() == expected);
    }

    @Test
    @DisplayName("config.json 을 고쳐 저장하면 1초 안에 한 번 알린다")
    void modifiedFileNotifiesOnceWithinASecond() throws IOException {
        // given
        Files.writeString(file(), "{ \"terminal\": { \"size\": 14 } }", StandardCharsets.UTF_8);
        watch();

        // when
        Files.writeString(file(), "{ \"terminal\": { \"size\": 16 } }", StandardCharsets.UTF_8);

        // then
        await().atMost(Duration.ofSeconds(1)).until(() -> calls.get() >= 1);
        assertCallsStay(1);
    }

    @Test
    @DisplayName("편집기처럼 임시 파일에 쓰고 이름을 바꿔 넣어도 알림은 한 번이다")
    void atomicRenameSaveNotifiesOnce() throws IOException {
        // given
        Files.writeString(file(), "{}", StandardCharsets.UTF_8);
        watch();

        // when
        Path tmp = dir.resolve("config.json.tmp~");
        Files.writeString(tmp, "{ \"terminal\": { \"size\": 16 } }", StandardCharsets.UTF_8);
        Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        // then
        await().atMost(Duration.ofSeconds(1)).until(() -> calls.get() >= 1);
        assertCallsStay(1);
    }

    @Test
    @DisplayName("없던 config.json 을 만들면 알린다")
    void createdFileNotifies() throws IOException {
        // given
        watch();

        // when
        Files.writeString(file(), "{}", StandardCharsets.UTF_8);

        // then
        await().atMost(Duration.ofSeconds(1)).until(() -> calls.get() == 1);
    }

    @Test
    @DisplayName("같은 폴더의 다른 파일(state.json 등)이 바뀌면 알리지 않는다")
    void otherFilesAreIgnored() throws IOException {
        // given
        watch();

        // when
        Files.writeString(dir.resolve("state.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("config.json.bak"), "{}", StandardCharsets.UTF_8);

        // then
        assertCallsStay(0);
    }

    @Test
    @DisplayName("폴더가 아직 없으면 만들고 감시한다")
    void missingDirectoryIsCreated() throws IOException {
        // given
        Path nested = dir.resolve("Tanager").resolve("config.json");
        watch(nested, calls::incrementAndGet);

        // when
        Files.writeString(nested, "{}", StandardCharsets.UTF_8);

        // then
        await().atMost(Duration.ofSeconds(1)).until(() -> calls.get() == 1);
    }

    @Test
    @DisplayName("구독을 끊으면 더 알리지 않는다")
    void cancelledWatchStopsNotifying() throws IOException {
        // given
        Subscription subscription = new WatchServiceConfigWatch(file()).watch(calls::incrementAndGet);

        // when
        subscription.cancel();
        subscription.cancel();
        Files.writeString(file(), "{}", StandardCharsets.UTF_8);

        // then
        assertCallsStay(0);
    }

    @Test
    @DisplayName("콜백은 가상 스레드에서 돌고, 던져도 다음 변경을 계속 알린다")
    void callbackRunsOnVirtualThreadAndSurvivesExceptions() throws IOException {
        // given
        List<Boolean> virtual = new CopyOnWriteArrayList<>();
        watch(file(), () -> {
            virtual.add(Thread.currentThread().isVirtual());
            calls.incrementAndGet();
            throw new IllegalStateException("콜백 버그");
        });

        // when
        Files.writeString(file(), "{}", StandardCharsets.UTF_8);
        await().atMost(Duration.ofSeconds(1)).until(() -> calls.get() == 1);
        Files.writeString(file(), "{ \"a\": 1 }", StandardCharsets.UTF_8);

        // then
        await().atMost(Duration.ofSeconds(1)).until(() -> calls.get() == 2);
        assertThat(virtual).containsOnly(true);
    }

    @Test
    @DisplayName("감시할 폴더를 만들 수 없으면 알리지 않는 구독을 돌려주고 던지지 않는다")
    void unwatchableDirectoryReturnsInertSubscription() throws IOException {
        // given
        Files.writeString(dir.resolve("blocker"), "x");

        // when
        Subscription subscription = new WatchServiceConfigWatch(
                        dir.resolve("blocker").resolve("config.json"))
                .watch(calls::incrementAndGet);

        // then
        subscription.cancel();
        assertThat(calls.get()).isZero();
    }
}
