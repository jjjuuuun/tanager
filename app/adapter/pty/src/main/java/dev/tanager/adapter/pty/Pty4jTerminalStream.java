package dev.tanager.adapter.pty;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import dev.tanager.application.port.out.Subscription;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.application.port.out.TerminalStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** pty4j 프로세스 하나를 {@link TerminalStream} 계약(ADR-0007)으로 감싼다. */
final class Pty4jTerminalStream implements TerminalStream {
    private static final System.Logger LOG = System.getLogger(Pty4jTerminalStream.class.getName());
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final long CLOSE_GRACE_SECONDS = 3;
    private static final int UNKNOWN_EXIT_CODE = -1;

    private final PtyProcess process;
    private final long pid;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();

    Pty4jTerminalStream(PtyProcess process) {
        this.process = process;
        this.pid = process.pid();
    }

    @Override
    public Subscription subscribe(Consumer<byte[]> onOutput) {
        if (isFinished()) {
            return () -> {};
        }
        Subscriber subscriber = new Subscriber(onOutput);
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("이미 시작한 터미널 스트림이다(pid " + pid + ")");
        }
        startReader();
    }

    @Override
    public void write(byte[] bytes) {
        if (isFinished()) {
            return;
        }
        synchronized (writeLock) {
            try {
                OutputStream in = process.getOutputStream();
                in.write(bytes);
                in.flush();
            } catch (IOException e) {
                // 확인과 쓰기 사이에 프로세스가 끝났다 — 끝난 뒤의 쓰기는 무시한다는 계약과 같다
                LOG.log(Level.DEBUG, "끝나는 중인 터미널에 쓰지 못했다(pid {0})", pid);
            }
        }
    }

    @Override
    public void resize(TerminalSize size) {
        if (isFinished()) {
            return;
        }
        try {
            process.setWinSize(new WinSize(size.columns(), size.rows()));
        } catch (IllegalStateException e) {
            // pty4j 는 이미 닫힌 PTY 의 크기 변경을 IllegalStateException 으로 알린다
            LOG.log(Level.DEBUG, "끝나는 중인 터미널의 크기를 바꾸지 못했다(pid {0})", pid);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        subscribers.clear();
        // 루트 종료 요청까지는 바로 한다(둘 다 기다리지 않는다) — 부른 쪽이 곧이어 작업 디렉터리를 지워도 덜 부딪힌다
        List<ProcessHandle> tree = processTree();
        process.destroy();
        if (started.compareAndSet(false, true)) {
            // 시작 전에 닫혔다 — 출력을 비우고 onExit 를 완료할 읽기 스레드가 있어야 한다
            startReader();
        }
        Thread.ofVirtual().name("pty-close-" + pid).start(() -> killSurvivors(tree));
    }

    @Override
    public CompletionStage<Integer> onExit() {
        return exit;
    }

    @Override
    public long pid() {
        return pid;
    }

    private boolean isFinished() {
        return closed.get() || exit.isDone();
    }

    private void startReader() {
        Thread.ofVirtual().name("pty-read-" + pid).start(this::readLoop);
    }

    private void readLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];
        InputStream out = process.getInputStream();
        try {
            int n;
            while ((n = out.read(buffer)) >= 0) {
                if (n > 0) {
                    deliver(buffer, n);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "터미널 출력 읽기가 끝났다(pid {0}): {1}", pid, e.getMessage());
        }
        int code = awaitExitCode();
        subscribers.clear();
        exit.complete(code);
    }

    private void deliver(byte[] buffer, int length) {
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.consumer().accept(Arrays.copyOf(buffer, length));
            } catch (RuntimeException e) {
                subscribers.remove(subscriber);
                // 출력 내용은 사용자 입력이 섞여 있어 남기지 않는다(java-style 12)
                LOG.log(Level.WARNING, "터미널 출력 구독자가 예외를 던져 해제했다(pid " + pid + ")", e);
            }
        }
    }

    private int awaitExitCode() {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UNKNOWN_EXIT_CODE;
        }
    }

    // 자손은 루트가 죽기 전에 잡아 둬야 한다 — 루트가 끝나면 부모 관계로 더는 찾을 수 없다
    private List<ProcessHandle> processTree() {
        Optional<ProcessHandle> root = ProcessHandle.of(pid);
        List<ProcessHandle> tree =
                new ArrayList<>(root.map(r -> r.descendants().toList()).orElse(List.of()));
        root.ifPresent(tree::add);
        return tree;
    }

    private void killSurvivors(List<ProcessHandle> tree) {
        awaitExitWithin(CLOSE_GRACE_SECONDS);
        for (ProcessHandle handle : tree) {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        }
    }

    private void awaitExitWithin(long seconds) {
        try {
            exit.get(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(Level.DEBUG, "터미널이 {0}초 안에 끝나지 않아 남은 프로세스를 강제 종료한다(pid {1})", seconds, pid);
        }
    }

    /** 같은 콜백을 두 번 구독해도 따로 해제되도록 구독마다 정체성을 가진 손잡이. */
    private static final class Subscriber {
        private final Consumer<byte[]> consumer;

        Subscriber(Consumer<byte[]> consumer) {
            this.consumer = consumer;
        }

        Consumer<byte[]> consumer() {
            return consumer;
        }
    }
}
