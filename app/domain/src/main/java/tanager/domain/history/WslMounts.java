package tanager.domain.history;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 배포판별 {@link WslMount}(#53). 배포판마다 wsl.conf 의 {@code [automount] root} 가 다를 수 있다. 표에 없는 배포판은 {@code
 * fallback} 이다. 배포판 이름은 탐지한 그대로(대소문자 구분) 찾는다.
 */
public record WslMounts(Map<String, WslMount> byDistro, WslMount fallback) {
    /** 아무 배포판도 모를 때 — 모두 기본 {@code /mnt/}. */
    public static final WslMounts DEFAULT = new WslMounts(Map.of(), WslMount.DEFAULT);

    public WslMounts {
        byDistro = Map.copyOf(byDistro);
        Objects.requireNonNull(fallback, "fallback");
    }

    /** 모든 배포판에 같은 마운트. */
    public static WslMounts uniform(WslMount mount) {
        return new WslMounts(Map.of(), mount);
    }

    /** 그 배포판의 마운트. 모르면 {@code fallback}. */
    public WslMount of(String distro) {
        return byDistro.getOrDefault(distro, fallback);
    }

    /** 그 배포판의 마운트를 표에 갖고 있는가. */
    public boolean knows(String distro) {
        return byDistro.containsKey(distro);
    }

    /** 그 배포판만 {@code mount} 로 바꾼 새 값. */
    public WslMounts with(String distro, WslMount mount) {
        Map<String, WslMount> next = new HashMap<>(byDistro);
        next.put(Objects.requireNonNull(distro, "distro"), Objects.requireNonNull(mount, "mount"));
        return new WslMounts(next, fallback);
    }
}
