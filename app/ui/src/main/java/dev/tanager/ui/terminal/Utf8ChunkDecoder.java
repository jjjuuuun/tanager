package dev.tanager.ui.terminal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 셸 출력 조각(UTF-8 바이트)을 문자로 바꾼다. 조각 경계에 걸친 멀티바이트 문자는 다음 조각이 올 때까지 들고 있고, 잘못된 바이트와
 * 끝에 남은 불완전한 바이트는 U+FFFD 로 바꾼다. 한 스레드(JediTerm 읽기 스레드)에서만 쓴다.
 */
final class Utf8ChunkDecoder {
    private static final byte[] NOTHING = new byte[0];

    private final CharsetDecoder decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private byte[] pending = NOTHING;

    /** 이 조각까지 완성된 문자들. 아직 완성되지 않은 꼬리 바이트는 다음 호출로 넘긴다. */
    String decode(byte[] chunk) {
        ByteBuffer in = ByteBuffer.allocate(pending.length + chunk.length);
        in.put(pending).put(chunk).flip();
        String out = run(in, false);
        pending = new byte[in.remaining()];
        in.get(pending);
        return out;
    }

    /** 입력이 끝났다. 남은 바이트가 있으면 U+FFFD 로 낸다. 이 뒤에 다시 쓸 수 있다. */
    String finish() {
        ByteBuffer in = ByteBuffer.wrap(pending);
        pending = NOTHING;
        String out = run(in, true);
        decoder.reset();
        return out;
    }

    private String run(ByteBuffer in, boolean endOfInput) {
        // UTF-8 은 바이트 하나가 문자 하나를 넘지 않는다(4바이트 → 서로게이트 2개, 잘못된 바이트 → U+FFFD 1개)
        CharBuffer out = CharBuffer.allocate(in.remaining() + 1);
        CoderResult result = decoder.decode(in, out, endOfInput);
        if (endOfInput && result.isUnderflow()) {
            result = decoder.flush(out);
        }
        if (result.isOverflow()) {
            throw new IllegalStateException("UTF-8 디코딩 버퍼가 모자랐다(버그)");
        }
        return out.flip().toString();
    }
}
