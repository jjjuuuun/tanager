package tanager.platform.windows;

import java.util.concurrent.atomic.AtomicReference;
import tanager.domain.history.WslMount;
import tanager.domain.history.WslMounts;

/** 지금까지 알아 낸 배포판별 마운트 루트(#53). 경로 변환과 디렉터리 확인이 같이 본다. 스레드 안전. */
final class WslMountRoots {
    private final AtomicReference<WslMounts> current = new AtomicReference<>(WslMounts.DEFAULT);

    /** 지금 값(불변). 모르는 배포판은 기본 {@code /mnt/} 다. */
    WslMounts snapshot() {
        return current.get();
    }

    /** 그 배포판의 루트를 이미 읽었는가. */
    boolean knows(String distro) {
        return current.get().knows(distro);
    }

    /** 그 배포판의 루트를 {@code mount} 로 알아 둔다. */
    void remember(String distro, WslMount mount) {
        current.updateAndGet(mounts -> mounts.with(distro, mount));
    }
}
