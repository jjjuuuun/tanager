package dev.tanager.application.port.out;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@link TerminalStream} 의 수작업 Fake. 테스트가 {@link #emit} · {@link #exit} 로 셸의 출력과 종료를 흉내 낸다.
 *
 * <p>계약을 지킨다. 콜백은 {@code emit} 을 부른 스레드에서, 이 객체의 모니터를 쥔 채로 온다(콜백 안에서 다른 스레드가 이
 * 스트림을 부르기를 기다리면 교착한다). {@link #whenStarted} 스크립트는 {@code start()} 를 부른 스레드에서 돈다. {@code
 * start()} 전의 {@code emit} · {@code exit} 는 버퍼에 쌓았다가 {@code start()} 때 순서대로 전달한다(구독자는 첫 바이트부터
 * 받는다). 끝난 뒤({@code exit} 처리 또는 {@code close})에는 구독 · 쓰기 · 크기 변경이 무시되고, {@code close} 뒤의 {@code
 * start()} 는 아무 일도 하지 않는다.
 */
public final class ScriptedTerminalStream implements TerminalStream {
    private final long pid;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    private final List<Consumer<byte[]>> subscribers = new CopyOnWriteArrayList<>();
    private final List<byte[]> pendingOutput = new ArrayList<>();
    private final List<byte[]> written = new CopyOnWriteArrayList<>();
    private final List<TerminalSize> resizes = new CopyOnWriteArrayList<>();
    private Consumer<ScriptedTerminalStream> onStart = stream -> {};
    private Integer pendingExit;
    private boolean started;
    private boolean finished;
    private boolean closed;
    private boolean echoWrites;
    private int closeCalls;

    public ScriptedTerminalStream(long pid) {
        this.pid = pid;
    }

    /** {@code start()} 가 불릴 때 실행할 시나리오(출력 · 종료 흉내). */
    public synchronized ScriptedTerminalStream whenStarted(Consumer<ScriptedTerminalStream> script) {
        this.onStart = script;
        return this;
    }

    /** 쓴 바이트를 그대로 출력으로 되돌려 준다(셸 에코 흉내). */
    public synchronized ScriptedTerminalStream echoWrites() {
        this.echoWrites = true;
        return this;
    }

    public void emit(String text) {
        emit(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 출력 한 조각을 구독자들에게 보낸다. 구독자마다 새 복사본을 준다. */
    public synchronized void emit(byte[] chunk) {
        if (finished) {
            return;
        }
        if (!started) {
            pendingOutput.add(chunk.clone());
            return;
        }
        deliver(chunk);
    }

    /** 셸이 {@code code} 로 끝났다고 흉내 낸다. 이미 낸 출력이 모두 전달된 뒤 onExit 가 완료된다. */
    public synchronized void exit(int code) {
        if (finished) {
            return;
        }
        if (!started) {
            pendingExit = code;
            return;
        }
        finish(code);
    }

    @Override
    public synchronized Subscription subscribe(Consumer<byte[]> onOutput) {
        if (finished) {
            return () -> {};
        }
        subscribers.add(onOutput);
        return () -> subscribers.remove(onOutput);
    }

    @Override
    public void start() {
        Consumer<ScriptedTerminalStream> script;
        synchronized (this) {
            if (closed) {
                return;
            }
            if (started) {
                throw new IllegalStateException("이미 시작했다");
            }
            started = true;
            script = onStart;
            for (byte[] chunk : pendingOutput) {
                deliver(chunk);
            }
            pendingOutput.clear();
            if (pendingExit != null) {
                finish(pendingExit);
                return;
            }
        }
        script.accept(this);
    }

    @Override
    public synchronized void write(byte[] bytes) {
        if (finished) {
            return;
        }
        written.add(bytes.clone());
        if (echoWrites && started) {
            deliver(bytes);
        }
    }

    @Override
    public synchronized void resize(TerminalSize size) {
        if (finished) {
            return;
        }
        resizes.add(size);
    }

    @Override
    public synchronized void close() {
        closeCalls++;
        closed = true;
        if (!finished) {
            // 계약: close 로 끝나도 onExit 는 완료된다. 코드는 의미 없다(Windows 는 1).
            pendingOutput.clear();
            finish(1);
        }
    }

    @Override
    public CompletionStage<Integer> onExit() {
        return exit.minimalCompletionStage();
    }

    @Override
    public long pid() {
        return pid;
    }

    /** {@code write} 로 받은 바이트들(불변 사본). 끝난 뒤의 쓰기는 기록하지 않는다. */
    public List<byte[]> written() {
        return written.stream().map(byte[]::clone).toList();
    }

    public List<TerminalSize> resizes() {
        return List.copyOf(resizes);
    }

    public synchronized int closeCalls() {
        return closeCalls;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    private void deliver(byte[] chunk) {
        for (Consumer<byte[]> subscriber : subscribers) {
            try {
                subscriber.accept(chunk.clone());
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {
                subscribers.remove(subscriber);
            }
        }
    }

    private void finish(int code) {
        finished = true;
        subscribers.clear();
        exit.complete(code);
    }
}
