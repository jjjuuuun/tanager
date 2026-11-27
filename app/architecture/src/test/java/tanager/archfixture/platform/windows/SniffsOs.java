package tanager.archfixture.platform.windows;

import com.formdev.flatlaf.util.SystemInfo;
import com.sun.jna.Platform;

/** 위반 픽스처: 플랫폼 모듈이 라이브러리의 OS 판별을 직접 쓴다(OS 분기는 bootstrap 한 곳). */
public class SniffsOs {
    boolean windows() {
        return Platform.isWindows() || SystemInfo.isWindows;
    }
}
