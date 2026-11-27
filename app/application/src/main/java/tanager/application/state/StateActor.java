package tanager.application.state;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.Subscription;

/**
 * 애플리케이션 상태 변경을 한 실행기에 직렬화한다(ADR-0012). 명령은 제출 순서대로 앞 명령의 결과 상태 위에서 돌고, 이벤트도 그 순서로
 * 나간다. 상태는 불변 값이라 {@link #snapshot()} 은 어느 스레드에서나 락 없이 읽는다.
 */
public final class StateActor {
    /** 운영 실행기 스레드 이름. */
    public static final String THREAD_NAME = "tanager-state";

    private static final System.Logger LOG = System.getLogger(StateActor.class.getName());

    private final Executor serial;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile AppState state;

    /**
     * @param initial 시작 상태
     * @param serial 일을 한 번에 하나씩 제출 순서대로 돌리는 실행기. 운영은 {@link #serialExecutor()}
     */
    public StateActor(AppState initial, Executor serial) {
        this.state = Objects.requireNonNull(initial, "initial");
        this.serial = Objects.requireNonNull(serial, "serial");
    }

    /** 운영 실행기: 단일 플랫폼 데몬 스레드 {@value #THREAD_NAME}. 앱 종료를 막지 않는다. */
    public static ExecutorService serialExecutor() {
        return Executors.newSingleThreadExecutor(
                task -> Thread.ofPlatform().name(THREAD_NAME).daemon(true).unstarted(task));
    }

    /**
     * 명령을 실행기에 넘기고 바로 돌아온다. 어느 스레드에서 불러도 된다. 응답은 상태 교체 · 이벤트 발행 뒤 완료된다. 명령이 던지면 상태를
     * 그대로 두고 이벤트 없이 그 예외로 완료된다. 명령 · 구독자가 {@link Error} 를 던지면 응답을 그 Error 로 완료한 뒤 다시 던진다. 실행기가
     * 일을 거부하면(종료 뒤) 던지지 않고 {@link RejectedExecutionException} 으로 실패한 응답을 돌려준다.
     *
     * <p>응답의 비동기 아닌 후속 단계({@code thenAccept} 등)는 응답을 완료하는 actor 스레드에서 돈다. UI 는
     * {@code thenAcceptAsync(fn, dispatcher::dispatch)} 로 EDT 에 넘긴다. actor 스레드(구독 콜백 · 후속 단계) 안에서 응답을
     * 기다리면({@code join} · {@code get}) 그 응답을 낼 스레드가 자신이라 교착한다 — 거기서는 submit 만 하고 기다리지 않는다. 그렇게 낸
     * 명령은 현재 명령의 응답 뒤에 돈다.
     */
    public <R> CompletionStage<R> submit(Command<R> command) {
        Objects.requireNonNull(command, "command");
        CompletableFuture<R> reply = new CompletableFuture<>();
        try {
            serial.execute(() -> run(command, reply));
        } catch (RejectedExecutionException e) {
            LOG.log(Level.WARNING, "실행기가 명령을 거부했다 — 종료 중이면 정상", e);
            reply.completeExceptionally(e);
        }
        return reply.minimalCompletionStage();
    }

    /** 마지막으로 확정된 상태(불변). 어느 스레드에서나 읽는다. 명령 도중의 상태는 보이지 않는다. */
    public AppState snapshot() {
        return state;
    }

    /**
     * 이벤트를 받는다. 콜백은 실행기 스레드에서 발행 순서대로 불린다 — 오래 막지 않는다. 콜백이 던지면 그 구독만 해제되고 다른 구독자는
     * 계속 받는다.
     */
    public Subscription subscribe(Consumer<AppEvent> listener) {
        Listener registration = new Listener(Objects.requireNonNull(listener, "listener"));
        listeners.add(registration);
        return () -> listeners.remove(registration);
    }

    // 실행기 스레드에서만 돈다. Error 는 실행기에 맡기되 제출한 쪽이 영원히 기다리지 않게 응답부터 완료한다
    private <R> void run(Command<R> command, CompletableFuture<R> reply) {
        try {
            apply(command, reply);
        } catch (Error e) {
            LOG.log(Level.ERROR, "명령 실행 중 Error — 응답을 실패로 완료하고 다시 던진다", e);
            reply.completeExceptionally(e);
            throw e;
        }
    }

    // state 를 쓰는 곳은 여기 하나다
    private <R> void apply(Command<R> command, CompletableFuture<R> reply) {
        Transition<R> transition;
        try {
            transition = Objects.requireNonNull(command.apply(state), "command 가 null 전이를 돌려줬다");
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "명령이 예외를 던졌다 — 상태를 바꾸지 않는다", e);
            reply.completeExceptionally(e);
            return;
        }
        state = transition.next();
        for (AppEvent event : transition.events()) {
            publish(event);
        }
        reply.complete(transition.reply());
    }

    private void publish(AppEvent event) {
        for (Listener listener : listeners) {
            try {
                listener.callback().accept(event);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "이벤트 구독자가 예외를 던져 구독을 해제한다", e);
                listeners.remove(listener);
            }
        }
    }

    // 같은 콜백을 두 번 구독해도 따로 해제되게 등록마다 객체 하나(동일성 비교)
    private static final class Listener {
        private final Consumer<AppEvent> callback;

        Listener(Consumer<AppEvent> callback) {
            this.callback = callback;
        }

        Consumer<AppEvent> callback() {
            return callback;
        }
    }
}
