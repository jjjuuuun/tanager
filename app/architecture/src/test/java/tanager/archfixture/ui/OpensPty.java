package tanager.archfixture.ui;

import com.pty4j.PtyProcessBuilder;

/** 위반 픽스처: UI 가 pty4j 를 직접 쓴다(ADR-0007: TerminalStream 포트를 거쳐야 한다). */
public class OpensPty {
    Object open() {
        return new PtyProcessBuilder(new String[] {"cmd.exe"});
    }
}
