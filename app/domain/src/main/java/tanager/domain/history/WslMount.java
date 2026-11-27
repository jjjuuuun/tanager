package tanager.domain.history;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * WSL 이 Windows 드라이브를 붙이는 루트(wsl.conf {@code [automount] root}, 기본 {@code /mnt/}, #90). 배포판마다 다를 수 있다 —
 * 배포판별 값은 {@link WslMounts}(#53).
 */
public record WslMount(String root) {
    /** WSL 기본값. */
    public static final WslMount DEFAULT = new WslMount("/mnt/");

    public WslMount {
        Objects.requireNonNull(root, "root");
        if (!root.startsWith("/") || !root.endsWith("/")) {
            throw new IllegalArgumentException("마운트 루트는 / 로 시작하고 / 로 끝나야 한다: " + root);
        }
    }

    /** 루트의 경로 조각("/mnt/" → [mnt], "/" → []). */
    List<String> segments() {
        return Arrays.stream(root.split("/")).filter(s -> !s.isEmpty()).toList();
    }
}
