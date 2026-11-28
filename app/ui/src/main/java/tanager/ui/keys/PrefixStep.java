package tanager.ui.keys;

import java.util.Objects;

/** prefix 상태 기계의 한 걸음: 다음 상태와 그 키의 판정(#193). {@link KeyRoute.Pass} 는 prefix 모드가 다루지 않은 키다. */
public record PrefixStep(PrefixState next, KeyRoute route) {
    public PrefixStep {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(route, "route");
    }
}
