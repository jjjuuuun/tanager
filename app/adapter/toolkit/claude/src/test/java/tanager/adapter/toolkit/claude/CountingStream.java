package tanager.adapter.toolkit.claude;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/** 밑 스트림에서 실제로 읽힌 바이트를 센다(읽기 상한 시험용). */
final class CountingStream extends FilterInputStream {
    private final AtomicLong count;

    CountingStream(InputStream in, AtomicLong count) {
        super(in);
        this.count = count;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            count.incrementAndGet();
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
        int n = super.read(buffer, off, len);
        if (n > 0) {
            count.addAndGet(n);
        }
        return n;
    }
}
