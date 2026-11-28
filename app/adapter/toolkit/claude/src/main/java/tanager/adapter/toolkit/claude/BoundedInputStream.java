package tanager.adapter.toolkit.claude;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 밑 스트림에서 {@code limit} 바이트까지만 읽는다. 그 뒤는 끝(-1)이다 — 위의 버퍼가 밑 스트림을 더 당기지 못하게 한다. */
final class BoundedInputStream extends FilterInputStream {

    private long remaining;

    BoundedInputStream(InputStream in, long limit) {
        super(in);
        this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int b = super.read();
        if (b >= 0) {
            remaining--;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (remaining <= 0) {
            return -1;
        }
        int n = super.read(b, off, (int) Math.min(len, remaining));
        if (n > 0) {
            remaining -= n;
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(Math.min(n, remaining));
        remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(super.available(), remaining);
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
