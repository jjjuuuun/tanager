package tanager.archfixture.adapter.pty;

import com.pty4j.PtyProcessBuilder;

/** 픽스처: pty 어댑터가 pty4j 를 쓰는 것은 허용된다. */
public class LegitPty {
    Object open() {
        return new PtyProcessBuilder(new String[] {"cmd.exe"});
    }
}
