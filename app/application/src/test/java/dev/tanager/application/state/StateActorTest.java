package dev.tanager.application.state;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tanager.application.port.out.DirectUiDispatcher;
import dev.tanager.application.port.out.Subscription;
import dev.tanager.domain.history.HistoryPolicy;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.history.WorkspacePath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StateActorTest {
    private static final WorkspacePath HOME =
            WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work").value().orElseThrow();

    private final ManualExecutor executor = new ManualExecutor();
    private final StateActor actor =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), executor);

    /** 방문 1회를 기록하고 HistoryChanged 를 내는 명령. 답은 방문 뒤 count. */
    private static Command<Integer> visit(WorkspacePath path) {
        return current -> {
            WorkHistory next = current.history().recordVisit(path, Instant.EPOCH);
            return new Transition<>(
                    current.withHistory(next),
                    List.of(new AppEvent.HistoryChanged(next)),
                    next.entries().get(path).visitCount());
        };
    }

    private static int count(AppState state) {
        var found = state.history().entries().get(HOME);
        return found == null ? 0 : found.visitCount();
    }

    @Test
    @DisplayName("제출한 명령은 실행기가 돌 때까지 상태를 바꾸지 않고 돈 뒤에 답으로 완료된다")
    void commandRunsOnExecutor() {
        // given
        var reply = actor.submit(visit(HOME)).toCompletableFuture();

        // when / then
        assertThat(reply).isNotDone();
        assertThat(count(actor.snapshot())).isZero();
        executor.runAll();
        assertThat(reply).isCompletedWithValue(1);
        assertThat(count(actor.snapshot())).isEqualTo(1);
    }

    @Test
    @DisplayName("명령 A 다음 B 는 A 의 결과 상태 위에서 돌고 이벤트도 A, B 순서로 나간다")
    void sequentialCommandsSeeEachOther() {
        // given
        var events = new ArrayList<AppEvent>();
        actor.subscribe(events::add);
        var a = actor.submit(visit(HOME)).toCompletableFuture();
        var b = actor.submit(visit(HOME)).toCompletableFuture();

        // when
        executor.runAll();

        // then
        assertThat(a).isCompletedWithValue(1);
        assertThat(b).isCompletedWithValue(2);
        assertThat(events).hasSize(2);
        assertThat(((AppEvent.HistoryChanged) events.get(0))
                        .history()
                        .entries()
                        .get(HOME)
                        .visitCount())
                .isEqualTo(1);
        assertThat(((AppEvent.HistoryChanged) events.get(1))
                        .history()
                        .entries()
                        .get(HOME)
                        .visitCount())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("상태를 바꾸지 않는 명령은 이벤트를 내지 않고 snapshot 은 같은 인스턴스다")
    void unchangedCommandPublishesNothing() {
        // given
        var events = new ArrayList<AppEvent>();
        actor.subscribe(events::add);
        AppState before = actor.snapshot();
        var reply = actor.submit(current -> new Transition<>(current, List.of(), "읽음"))
                .toCompletableFuture();

        // when
        executor.runAll();

        // then
        assertThat(reply).isCompletedWithValue("읽음");
        assertThat(events).isEmpty();
        assertThat(actor.snapshot()).isSameAs(before);
    }

    @Test
    @DisplayName("예외를 던지는 명령은 상태를 그대로 두고 응답을 예외로 완료하며 다음 명령은 정상이다")
    void throwingCommandLeavesStateUnchanged() {
        // given
        var events = new ArrayList<AppEvent>();
        actor.subscribe(events::add);
        AppState before = actor.snapshot();
        Command<Integer> boom = current -> {
            throw new IllegalStateException("버그");
        };
        var failed = actor.submit(boom).toCompletableFuture();
        var next = actor.submit(visit(HOME)).toCompletableFuture();

        // when
        executor.runNext();

        // then
        assertThat(failed).isCompletedExceptionally();
        assertThatThrownBy(() -> failed.get(10, SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(actor.snapshot()).isSameAs(before);
        assertThat(events).isEmpty();

        // when
        executor.runNext();

        // then
        assertThat(next).isCompletedWithValue(1);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("명령이 Error 를 던지면 응답을 그 Error 로 완료하고 상태를 그대로 둔 채 Error 를 다시 던진다")
    void commandErrorCompletesReplyThenRethrows() {
        // given
        var events = new ArrayList<AppEvent>();
        actor.subscribe(events::add);
        AppState before = actor.snapshot();
        Command<Integer> fatal = current -> {
            throw new FatalTestError();
        };
        var failed = actor.submit(fatal).toCompletableFuture();

        // when / then
        assertThatThrownBy(executor::runNext).isInstanceOf(FatalTestError.class);
        assertThat(failed).isCompletedExceptionally();
        assertThatThrownBy(() -> failed.get(10, SECONDS)).hasCauseInstanceOf(FatalTestError.class);
        assertThat(actor.snapshot()).isSameAs(before);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("구독자가 Error 를 던져도 응답은 그 Error 로 완료되고 Error 는 다시 던져진다")
    void subscriberErrorStillCompletesReply() {
        // given
        actor.subscribe(e -> {
            throw new FatalTestError();
        });
        var reply = actor.submit(visit(HOME)).toCompletableFuture();

        // when / then
        assertThatThrownBy(executor::runNext).isInstanceOf(FatalTestError.class);
        assertThat(reply).isCompletedExceptionally();
        assertThatThrownBy(() -> reply.get(10, SECONDS)).hasCauseInstanceOf(FatalTestError.class);
    }

    @Test
    @DisplayName("실행기가 일을 거부하면 submit 은 던지지 않고 거부 예외로 실패한 응답을 돌려준다")
    void rejectedSubmitReturnsFailedReply() {
        // given
        StateActor closed = new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), task -> {
            throw new RejectedExecutionException("종료됨");
        });

        // when
        var reply = closed.submit(visit(HOME)).toCompletableFuture();

        // then
        assertThat(reply).isCompletedExceptionally();
        assertThatThrownBy(() -> reply.get(10, SECONDS)).hasCauseInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("구독자가 콜백 안에서 낸 명령은 현재 응답이 완료된 뒤에 돌고 교착하지 않는다")
    void reentrantSubmitRunsAfterCurrentReply() {
        // given
        var log = new ArrayList<String>();
        var inner = new ArrayList<CompletableFuture<Integer>>();
        actor.subscribe(e -> {
            int count =
                    ((AppEvent.HistoryChanged) e).history().entries().get(HOME).visitCount();
            log.add("event " + count);
            if (inner.isEmpty()) {
                CompletableFuture<Integer> reply = actor.submit(visit(HOME)).toCompletableFuture();
                reply.thenAccept(v -> log.add("reply " + v));
                inner.add(reply);
            }
        });
        actor.submit(visit(HOME)).thenAccept(v -> log.add("reply " + v));

        // when
        executor.runNext();

        // then
        assertThat(log).containsExactly("event 1", "reply 1");
        assertThat(inner.getFirst()).isNotDone();
        assertThat(executor.pending()).isEqualTo(1);

        // when
        executor.runAll();

        // then
        assertThat(log).containsExactly("event 1", "reply 1", "event 2", "reply 2");
        assertThat(inner.getFirst()).isCompletedWithValue(2);
    }

    @Test
    @DisplayName("구독자 둘 중 하나가 던지면 그 구독자만 해제되고 다른 구독자는 계속 받는다")
    void throwingSubscriberIsRemovedAlone() {
        // given
        var good = new ArrayList<AppEvent>();
        var badCalls = new ArrayList<AppEvent>();
        actor.subscribe(e -> {
            badCalls.add(e);
            throw new IllegalStateException("구독자 버그");
        });
        actor.subscribe(good::add);

        // when
        actor.submit(visit(HOME));
        actor.submit(visit(HOME));
        executor.runAll();

        // then
        assertThat(badCalls).hasSize(1);
        assertThat(good).hasSize(2);
        assertThat(count(actor.snapshot())).isEqualTo(2);
    }

    @Test
    @DisplayName("구독을 취소하면 이후 이벤트를 받지 않는다")
    void cancelledSubscriptionGetsNothing() {
        // given
        var events = new ArrayList<AppEvent>();
        Subscription subscription = actor.subscribe(events::add);
        subscription.cancel();

        // when
        actor.submit(visit(HOME));
        executor.runAll();

        // then
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("subscribeOnUi 의 콜백은 UiDispatcher 를 거쳐서 온다")
    void uiSubscriptionGoesThroughDispatcher() {
        // given
        var dispatcher = new DirectUiDispatcher();
        var events = new ArrayList<AppEvent>();
        Consumer<AppEvent> listener = events::add;
        new UiAppEvents(actor, dispatcher).subscribeOnUi(listener);

        // when
        actor.submit(visit(HOME));
        executor.runAll();

        // then
        assertThat(dispatcher.dispatchCount()).isEqualTo(1);
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("subscribeOnUi 구독을 취소하면 디스패처도 더 부르지 않는다")
    void uiSubscriptionCancel() {
        // given
        var dispatcher = new DirectUiDispatcher();
        Subscription subscription = new UiAppEvents(actor, dispatcher).subscribeOnUi(e -> {});
        subscription.cancel();

        // when
        actor.submit(visit(HOME));
        executor.runAll();

        // then
        assertThat(dispatcher.dispatchCount()).isZero();
    }

    @Test
    @DisplayName("응답 future 는 이벤트가 발행된 뒤에 완료된다")
    void replyCompletesAfterEvents() {
        // given
        var log = new ArrayList<String>();
        actor.subscribe(e -> log.add("event"));
        CompletableFuture<Integer> reply = actor.submit(visit(HOME)).toCompletableFuture();
        reply.thenAccept(v -> log.add("reply"));

        // when
        executor.runAll();

        // then
        assertThat(log).containsExactly("event", "reply");
    }

    /** 테스트가 일부러 던지는 Error — JUnit · AssertJ 의 AssertionError 와 섞이지 않게 따로 둔다. */
    private static final class FatalTestError extends Error {
        FatalTestError() {
            super("테스트용 Error");
        }
    }
}
