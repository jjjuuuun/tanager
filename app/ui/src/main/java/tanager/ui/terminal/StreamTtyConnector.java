package tanager.ui.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.TtyConnectorResizeStrategy;
import com.jediterm.terminal.TtyConnectorResizeStrategyProvider;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import tanager.application.port.out.Subscription;
import tanager.application.port.out.TerminalStream;

/**
 * push 포트 {@link TerminalStream} 을 JediTerm 의 pull 인터페이스 {@link TtyConnector} 로 잇는다(ADR-0011).
 *
 * <p>구독 콜백(셸 읽기 스레드)은 바이트 조각을 크기 제한 큐에 넣기만 한다 — 큐가 차면 기다려 셸 출력을 멈춘다(배압). 디코딩은
 * JediTerm 읽기 스레드가 {@link #read} 에서 한다. 셸이 끝나면({@code onExit}, 모든 조각 전달 뒤) EOF 표식을 넣어 남은 출력을
 * 다 읽은 다음 {@code read} 가 -1 을 준다. {@link #write} 는 JediTerm 의 executor 에서 불린다(EDT 아님).
 */
public final class StreamTtyConnector implements TtyConnector, TtyConnectorResizeStrategyProvider {
    private static final int CAPACITY = 1024;
    private static final long OFFER_RETRY_MILLIS = 200;
    // 표식은 객체 동일성으로 구분한다. 스트림이 주는 조각은 늘 새 배열이라 겹치지 않는다
    private static final byte[] EOF = new byte[0];

    private final TerminalStream stream;
    private final String name;
    private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>(CAPACITY);
    private final CompletableFuture<Integer> exitCode = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean endOfStream;
    private volatile Subscription subscription = () -> {};

    // 읽기 스레드 전용
    private final Utf8ChunkDecoder decoder = new Utf8ChunkDecoder();
    private String decoded = "";
    private int decodedOffset;

    private StreamTtyConnector(TerminalStream stream, String name) {
        this.stream = Objects.requireNonNull(stream, "stream");
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * 스트림을 구독하고 종료를 EOF 로 잇는다. {@link TerminalStream#start()} 는 부르지 않는다 — 위젯에 붙인 뒤 부른 쪽이
     * 시작한다(ADR-0007 의 순서).
     */
    public static StreamTtyConnector attach(TerminalStream stream, String name) {
        StreamTtyConnector connector = new StreamTtyConnector(stream, name);
        connector.subscription = stream.subscribe(connector::enqueue);
        stream.onExit().whenComplete((code, error) -> connector.markEnd(code == null ? -1 : code));
        return connector;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws InterruptedIOException {
        if (length == 0) {
            return 0;
        }
        if (closed.get()) {
            // 닫힌 뒤에는 막혀 있던 생산자가 넣은 조각이 큐에 남아 있어도 읽지 않는다 — EOF 가 마지막이다
            return -1;
        }
        while (decodedOffset >= decoded.length()) {
            if (endOfStream || closed.get()) {
                return -1;
            }
            byte[] chunk = take();
            if (chunk == EOF) {
                endOfStream = true;
                replaceDecoded(decoder.finish());
            } else {
                replaceDecoded(decoder.decode(chunk));
            }
        }
        int count = Math.min(length, decoded.length() - decodedOffset);
        // 서로게이트 쌍을 두 번의 read 로 쪼개지 않는다
        if (count > 1 && count < decoded.length() - decodedOffset) {
            if (Character.isHighSurrogate(decoded.charAt(decodedOffset + count - 1))) {
                count--;
            }
        }
        decoded.getChars(decodedOffset, decodedOffset + count, buf, offset);
        decodedOffset += count;
        return count;
    }

    @Override
    public void write(byte[] bytes) {
        if (!closed.get()) {
            stream.write(bytes);
        }
    }

    @Override
    public void write(String string) {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    /** EOF 를 읽기 전이고 닫지 않았다. */
    @Override
    public boolean isConnected() {
        return !endOfStream && !closed.get();
    }

    /** 1 미만은 1, 상한은 {@link TerminalSize} 의 범위로 맞춘다. */
    @Override
    public void resize(TermSize size) {
        stream.resize(TerminalSizes.of(size.getColumns(), size.getRows()));
    }

    @Override
    public int waitFor() throws InterruptedException {
        try {
            return exitCode.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("종료 코드를 받지 못했다(버그)", e.getCause());
        }
    }

    @Override
    public boolean ready() {
        return decodedOffset < decoded.length() || !chunks.isEmpty();
    }

    @Override
    public String getName() {
        return name;
    }

    /** 구독을 끊고 막혀 있는 {@link #read} 를 -1 로 풀고 스트림(프로세스)을 닫는다. 여러 번 불러도 된다. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        subscription.cancel();
        do {
            chunks.clear();
        } while (!chunks.offer(EOF));
        stream.close();
    }

    /** ConPTY 는 크기 변경 직후 화면 전체를 다시 보내므로 JediTerm 이 크기 변경을 모아 늦게 보낸다. */
    @Override
    public TtyConnectorResizeStrategy getResizeStrategy() {
        return TtyConnectorResizeStrategy.POSTPONED;
    }

    private void enqueue(byte[] chunk) {
        put(chunk);
    }

    private void markEnd(int code) {
        exitCode.complete(code);
        put(EOF);
    }

    // 큐가 차면 기다리되(배압), 닫히면 버린다. 시간 제한을 두고 다시 확인해 close 뒤 늦어도 그만큼 안에 풀린다
    // (생산자가 스트림의 잠금을 쥔 채 막혀 있으면 close 의 stream.close() 가 그 잠금을 기다린다)
    private void put(byte[] chunk) {
        try {
            while (!closed.get()) {
                if (chunks.offer(chunk, OFFER_RETRY_MILLIS, TimeUnit.MILLISECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private byte[] take() throws InterruptedIOException {
        try {
            return chunks.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("터미널 읽기가 중단됐다");
            interrupted.initCause(e);
            throw interrupted;
        }
    }

    private void replaceDecoded(String text) {
        decoded = text;
        decodedOffset = 0;
    }
}
