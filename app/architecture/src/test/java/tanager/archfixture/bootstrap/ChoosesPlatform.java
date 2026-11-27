package tanager.archfixture.bootstrap;

import com.sun.jna.Platform;

/** 허용 픽스처: 조립 루트(bootstrap)는 OS 를 판별해도 된다. */
public class ChoosesPlatform {
    boolean windows() {
        return Platform.isWindows();
    }
}
