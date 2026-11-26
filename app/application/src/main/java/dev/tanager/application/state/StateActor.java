package dev.tanager.application.state;

import dev.tanager.application.port.out.Subscription;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
     * 그대로 두고 이벤트 없이 그 예외로 완료된다.
     */
    public <R> CompletionStage<R> submit(Command<R> command) {
        Objects.requireNonNull(command, "command");
        CompletableFuture<R> reply = new CompletableFuture<>();
        serial.execute(() -> run(command, reply));
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

    // 실행기 스레드에서만 돈다 — state 를 쓰는 곳은 여기 하나다
    private <R> void run(Command<R> command, CompletableFuture<R> reply) {
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
