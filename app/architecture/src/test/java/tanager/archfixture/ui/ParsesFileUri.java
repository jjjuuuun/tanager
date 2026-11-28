package tanager.archfixture.ui;

import java.net.URI;

/** 허용 픽스처: OSC 7 의 file:// 경로를 URI 로 해석만 한다 — 밖에 나가지 않는다. */
public final class ParsesFileUri {
    String path() {
        return URI.create("file:///").getPath();
    }
}
