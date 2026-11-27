package tanager.application.port.out;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** 테스트용 구독자: 받은 바이트를 모아 UTF-8 문자열로 보여 준다. 스레드 안전. */
public final class OutputCollector implements Consumer<byte[]> {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private int calls;

    @Override
    public synchronized void accept(byte[] chunk) {
        buffer.writeBytes(chunk);
        calls++;
    }

    /** 지금까지 받은 전체를 UTF-8 로 디코딩한 문자열(조각 경계에 걸린 멀티바이트 문자도 온전하다). */
    public synchronized String text() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    public synchronized byte[] bytes() {
        return buffer.toByteArray();
    }

    /** 콜백이 불린 횟수. */
    public synchronized int calls() {
        return calls;
    }
}
