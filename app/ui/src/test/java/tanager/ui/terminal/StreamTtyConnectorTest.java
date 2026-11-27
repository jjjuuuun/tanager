package tanager.ui.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnectorResizeStrategy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.application.port.out.TerminalSize;

class StreamTtyConnectorTest {
    private final ScriptedTerminalStream stream = new ScriptedTerminalStream(7);

    private static String text(java.util.List<byte[]> chunks) {
        StringBuilder out = new StringBuilder();
        for (byte[] chunk : chunks) {
            out.append(new String(chunk, StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    @Test
    @DisplayName("시작 전에 쌓인 조각을 작은 버퍼로 반복해 읽어도 순서대로 빠짐없이 나온다")
    void readsEverythingInOrderThroughSmallBuffer() throws IOException {
        // given
        StreamTtyConnector connector = StreamTtyConnector.attach(stream, "test");
        stream.emit("a");
        stream.emit("b한");
        stream.exit(0);
        stream.start();

        // when
        StringBuilder read = new StringBuilder();
        char[] buf = new char[2];
        int n;
        while ((n = connector.read(buf, 0, buf.length)) != -1) {
            assertThat(n).isPositive();
            read.append(buf, 0, n);
        }

        // then
        assertThat(read.toString()).isEqualTo("ab한");
    }

    @Test
    @DisplayName("셸이 끝나면 남은 출력을 다 읽은 뒤 -1 이 나오고 연결이 끊긴 것으로 보이며 waitFor 는 종료 코드를 준다")
    void exitBecomesEofAfterDrain() throws Exception {
        // given
        StreamTtyConnector connector = StreamTtyConnector.attach(stream, "test");
        stream.emit("x");
        stream.exit(3);
        stream.start();
        char[] buf = new char[8];

        // when
        int first = connector.read(buf, 0, buf.length);
        boolean connectedBeforeEof = connector.isConnected();
        int second = connector.read(buf, 0, buf.length);

        // then
        assertThat(first).isEqualTo(1);
        assertThat(connectedBeforeEof).isTrue();
        assertThat(second).isEqualTo(-1);
        assertThat(connector.isConnected()).isFalse();
        assertThat(connector.waitFor()).isEqualTo(3);
    }

    @Test
    @DisplayName("read 가 막혀 있을 때 close 하면 -1 로 풀리고 스트림도 닫는다")
    void closeReleasesBlockedRead() {
        // given
        StreamTtyConnector connector = StreamTtyConnector.attach(stream, "test");
        stream.start();
        Thread[] reader = new Thread[1];
        CompletableFuture<Integer> result = CompletableFuture.supplyAsync(() -> {
            reader[0] = Thread.currentThread();
            try {
                return connector.read(new char[8], 0, 8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        await().atMost(Duration.ofSeconds(2)).until(() -> reader[0] != null && isWaiting(reader[0]));

        // when
        connector.close();

        // then
        assertThat(result.orTimeout(2, java.util.concurrent.TimeUnit.SECONDS).join())
                .isEqualTo(-1);
        assertThat(stream.closeCalls()).isGreaterThanOrEqualTo(1);
        connector.close();
        assertThat(connector.isConnected()).isFalse();
    }

    private static boolean isWaiting(Thread t) {
        Thread.State state = t.getState();
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    @Test
    @DisplayName("크기 변경은 1 미만을 1 로 맞춰 스트림에 전하고 쓰기는 UTF-8 이며 리사이즈 전략은 POSTPONED 다")
    void resizeAndWrite() throws IOException {
        // given
        StreamTtyConnector connector = StreamTtyConnector.attach(stream, "test");
        stream.start();

        // when
        connector.resize(new TermSize(0, 0));
        connector.resize(new TermSize(120, 40));
        connector.write("한");

        // then
        assertThat(stream.resizes()).containsExactly(new TerminalSize(1, 1), new TerminalSize(120, 40));
        assertThat(text(stream.written())).isEqualTo("한");
        assertThat(connector.getResizeStrategy()).isEqualTo(TtyConnectorResizeStrategy.POSTPONED);
    }

    @Test
    @DisplayName("이모지(대리 쌍)를 크기 2 버퍼로 읽어도 쌍을 쪼개지 않고, 크기 1 이어도 빠짐없이 읽힌다")
    void readsSurrogatePairsWithTinyBuffers() throws IOException {
        for (int size : new int[] {1, 2}) {
            // given
            ScriptedTerminalStream emojis = new ScriptedTerminalStream(9);
            StreamTtyConnector connector = StreamTtyConnector.attach(emojis, "emoji");
            String text = "a😀b🎉";
            emojis.emit(text);
            emojis.exit(0);
            emojis.start();

            // when
            StringBuilder read = new StringBuilder();
            char[] buf = new char[size];
            int n;
            while ((n = connector.read(buf, 0, buf.length)) != -1) {
                if (size == 2) {
                    assertThat(Character.isHighSurrogate(buf[n - 1]))
                            .as("크기 2 버퍼의 끝에 홀로 남은 상위 대리 문자")
                            .isFalse();
                }
                read.append(buf, 0, n);
            }

            // then
            assertThat(read.toString()).as("버퍼 크기 %d", size).isEqualTo(text);
        }
    }

    @Test
    @DisplayName("큐(조각 1024개)가 가득 차면 생산자가 기다리고, close 는 그 대기를 풀며 그 뒤 read 는 바로 -1 이다")
    void backpressureAndCloseReleaseProducer() throws Exception {
        // given
        StreamTtyConnector connector = StreamTtyConnector.attach(stream, "full");
        stream.start();
        Thread producer = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < 1025; i++) {
                stream.emit("x");
            }
        });

        // then 1025번째 조각에서 생산자가 막힌다
        await().atMost(Duration.ofSeconds(5)).until(() -> isWaiting(producer));
        assertThat(connector.ready()).isTrue();

        // when
        connector.close();

        // then
        producer.join(Duration.ofSeconds(2));
        assertThat(producer.isAlive()).isFalse();
        assertThat(connector.read(new char[8], 0, 8)).isEqualTo(-1);
    }
}
