package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tanager.domain.shared.Result;

/**
 * {@link PtyLauncher} · {@link TerminalStream} 계약(ADR-0007). Fake 와 실제 구현이 함께 상속한다.
 *
 * <p>하위 클래스는 시나리오 훅 네 개를 채운다. Fake 는 훅 안에서 스크립트를 짜고, 실제 구현은 OS 의 실제 명령을 만든다.
 */
@Tag("contract")
public abstract class PtyLauncherContract {
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final List<TerminalStream> opened = new ArrayList<>();

    /** 검사할 런처. 같은 테스트 안에서는 같은 인스턴스를 돌려준다. */
    protected abstract PtyLauncher launcher();

    /** {@code text} 를 출력하고 종료 코드 {@code code} 로 끝나는 프로세스를 띄우는 명령(훅이 시나리오도 준비한다). */
    protected abstract LaunchCommand printsThenExits(String text, int code);

    /** 입력을 기다리며 계속 살아 있는 대화형 프로세스를 띄우는 명령. */
    protected abstract LaunchCommand interactive();

    /** {@link #interactive()} 에 쓰면 {@code text} 가 출력에 나오게 하는 입력 바이트. */
    protected abstract byte[] echoLine(String text);

    @AfterEach
    void closeOpenedStreams() {
        // close 는 바로 돌아온다 — 다음 테스트 · 임시 폴더 정리와 부딪히지 않게 끝날 때까지 기다린다
        opened.forEach(TerminalStream::close);
        opened.forEach(PtyLauncherContract::awaitExit);
    }

    private TerminalStream open(LaunchCommand command) {
        Result<TerminalStream, LaunchError> result = launcher().launch(command, TerminalSize.DEFAULT);
        assertThat(result.isOk()).as("기동 결과 %s", result).isTrue();
        TerminalStream stream = result.value().orElseThrow();
        opened.add(stream);
        return stream;
    }

    private static void awaitExit(TerminalStream stream) {
        await().atMost(WAIT).until(() -> stream.onExit().toCompletableFuture().isDone());
    }

    @Test
    @DisplayName("C1 구독하고 시작하면 출력을 받고 종료 코드로 onExit 가 완료된다")
    public void receivesOutputAndExitCode() {
        // given
        TerminalStream stream = open(printsThenExits("hi", 0));
        OutputCollector out = new OutputCollector();
        stream.subscribe(out);

        // when
        stream.start();

        // then
        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT).isEqualTo(0);
        assertThat(out.text()).contains("hi");
    }

    @Test
    @DisplayName("C1 종료 코드가 0 이 아니어도 그대로 전달된다")
    public void propagatesNonZeroExitCode() {
        TerminalStream stream = open(printsThenExits("hi", 7));
        stream.subscribe(new OutputCollector());

        stream.start();

        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT).isEqualTo(7);
    }

    @Test
    @DisplayName("C2 onExit 가 완료되는 시점에 모든 출력이 이미 전달돼 있고 완료는 한 번뿐이다")
    public void exitCompletesAfterAllOutputDelivered() {
        // given
        TerminalStream stream = open(printsThenExits("hi", 0));
        OutputCollector out = new OutputCollector();
        stream.subscribe(out);
        AtomicReference<String> seenAtExit = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        stream.onExit().thenAccept(code -> {
            seenAtExit.set(out.text());
            completions.incrementAndGet();
        });

        // when
        stream.start();
        awaitExit(stream);

        // then
        await().atMost(WAIT).until(() -> completions.get() > 0);
        assertThat(seenAtExit.get()).contains("hi");
        assertThat(completions.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("C3 구독자가 둘이면 같은 바이트열을 각각 받는다")
    public void everySubscriberGetsTheSameBytes() {
        // given
        TerminalStream stream = open(printsThenExits("hi", 0));
        OutputCollector first = new OutputCollector();
        OutputCollector second = new OutputCollector();
        stream.subscribe(first);
        stream.subscribe(second);

        // when
        stream.start();
        awaitExit(stream);

        // then
        assertThat(first.bytes()).isNotEmpty().isEqualTo(second.bytes());
    }

    @Test
    @DisplayName("C4 구독자가 예외를 던지면 그 구독자만 해제되고 다른 구독자는 계속 받는다")
    public void throwingSubscriberIsDroppedOthersContinue() {
        // given
        TerminalStream stream = open(printsThenExits("hi", 0));
        AtomicInteger throwingCalls = new AtomicInteger();
        stream.subscribe(chunk -> {
            throwingCalls.incrementAndGet();
            throw new IllegalStateException("구독자 오류");
        });
        OutputCollector healthy = new OutputCollector();
        stream.subscribe(healthy);

        // when
        stream.start();
        awaitExit(stream);

        // then
        assertThat(healthy.text()).contains("hi");
        assertThat(throwingCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("C4b 구독자가 Error 를 던져도 그 구독자만 해제되고 onExit 가 완료된다")
    public void subscriberThrowingErrorIsDroppedAndExitStillCompletes() {
        // given
        TerminalStream stream = open(printsThenExits("hi", 0));
        AtomicInteger throwingCalls = new AtomicInteger();
        stream.subscribe(chunk -> {
            throwingCalls.incrementAndGet();
            throw new AssertionError("구독자 오류");
        });
        OutputCollector healthy = new OutputCollector();
        stream.subscribe(healthy);

        // when
        stream.start();

        // then
        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT).isEqualTo(0);
        assertThat(healthy.text()).contains("hi");
        assertThat(throwingCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("C5 cancel 한 구독자는 더 받지 않고 cancel 은 여러 번 해도 된다")
    public void cancelledSubscriberReceivesNothingMore() {
        // given
        TerminalStream stream = open(interactive());
        OutputCollector cancelled = new OutputCollector();
        OutputCollector kept = new OutputCollector();
        Subscription subscription = stream.subscribe(cancelled);
        stream.subscribe(kept);
        subscription.cancel();
        stream.start();

        // when
        stream.write(echoLine("after-cancel"));

        // then
        await().atMost(WAIT).until(() -> kept.text().contains("after-cancel"));
        assertThat(cancelled.text()).doesNotContain("after-cancel");
        assertThatCode(subscription::cancel).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("C6 close 는 두 번 불러도 안전하고 onExit 가 완료되며 이후 write · resize 는 무시된다")
    public void closeIsIdempotentAndLaterCallsAreIgnored() {
        // given
        TerminalStream stream = open(interactive());
        OutputCollector out = new OutputCollector();
        stream.subscribe(out);
        stream.start();

        // when
        stream.close();
        assertThatCode(stream::close).doesNotThrowAnyException();

        // then
        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT);
        String before = out.text();
        assertThatCode(() -> {
                    stream.write(echoLine("after-close"));
                    stream.resize(new TerminalSize(100, 30));
                })
                .doesNotThrowAnyException();
        assertThat(out.text()).isEqualTo(before).doesNotContain("after-close");
    }

    @Test
    @DisplayName("C7 start 를 두 번 부르면 IllegalStateException")
    public void startTwiceFails() {
        TerminalStream stream = open(interactive());
        stream.start();

        assertThatThrownBy(stream::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("C8 끝난 스트림에 구독하면 cancel 가능한 Subscription 을 받고 콜백은 없다")
    public void subscribingAfterExitIsANoOp() {
        // given
        TerminalStream stream = open(printsThenExits("hi", 0));
        stream.subscribe(new OutputCollector());
        stream.start();
        awaitExit(stream);
        OutputCollector late = new OutputCollector();

        // when
        Subscription subscription = stream.subscribe(late);

        // then
        assertThat(subscription).isNotNull();
        assertThatCode(subscription::cancel).doesNotThrowAnyException();
        assertThat(late.calls()).isZero();
    }

    @Test
    @DisplayName("C9 write 한 입력이 대화형 프로세스의 출력으로 돌아온다")
    public void writtenInputProducesOutput() {
        // given
        TerminalStream stream = open(interactive());
        OutputCollector out = new OutputCollector();
        stream.subscribe(out);
        stream.start();

        // when
        stream.write(echoLine("C9MARK"));

        // then
        await().atMost(WAIT).until(() -> out.text().contains("C9MARK"));
    }

    @Test
    @DisplayName("C10 pid 는 양수이고 start 전에도 알 수 있다")
    public void pidIsPositive() {
        TerminalStream stream = open(interactive());

        assertThat(stream.pid()).isPositive();
    }

    @Test
    @DisplayName("C11 끝난 스트림의 close 는 아무 일도 하지 않고 onExit 값도 그대로다")
    public void closeAfterExitChangesNothing() {
        // given
        TerminalStream stream = open(printsThenExits("hi", 7));
        stream.subscribe(new OutputCollector());
        stream.start();
        awaitExit(stream);

        // when
        assertThatCode(stream::close).doesNotThrowAnyException();

        // then
        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT).isEqualTo(7);
    }

    @Test
    @DisplayName("C12 시작 전에 close 해도 onExit 가 완료된다")
    public void closeBeforeStartCompletesExit() {
        // given
        TerminalStream stream = open(interactive());

        // when
        stream.close();

        // then
        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT);
    }

    @Test
    @DisplayName("C13 close 뒤 start 는 예외 없이 아무 일도 하지 않는다")
    public void startAfterCloseIsANoOp() {
        // given
        TerminalStream stream = open(interactive());
        OutputCollector out = new OutputCollector();
        stream.subscribe(out);
        stream.close();
        awaitExit(stream);

        // when
        assertThatCode(stream::start).doesNotThrowAnyException();

        // then
        stream.write(echoLine("after-close"));
        assertThat(out.text()).doesNotContain("after-close");
    }

    @Test
    @DisplayName("C14 onExit 로 받은 것을 완료시켜도 실제 종료 코드가 유지된다")
    public void onExitCannotBeCompletedByCallers() {
        // given
        TerminalStream stream = open(printsThenExits("hi", 7));
        stream.subscribe(new OutputCollector());

        // when
        stream.onExit().toCompletableFuture().complete(99);
        stream.start();

        // then
        assertThat(stream.onExit().toCompletableFuture()).succeedsWithin(WAIT).isEqualTo(7);
    }

    /** 하위 클래스가 UTF-8 입력 바이트를 만들 때 쓰는 도우미. */
    protected static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
