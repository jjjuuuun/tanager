package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 배포판별 automount 루트(#53). */
class WslMountsTest {

    @Test
    @DisplayName("모르는 배포판은 기본 마운트(/mnt/)다")
    void unknownDistroUsesFallback() {
        // when
        WslMount mount = WslMounts.DEFAULT.of("Ubuntu");

        // then
        assertThat(mount).isEqualTo(WslMount.DEFAULT);
        assertThat(WslMounts.DEFAULT.knows("Ubuntu")).isFalse();
    }

    @Test
    @DisplayName("with 는 그 배포판만 바꾼 새 값을 돌려주고 원래 값은 그대로다")
    void withReturnsNewValue() {
        // given
        WslMount win = new WslMount("/win/");

        // when
        WslMounts learned = WslMounts.DEFAULT.with("Ubuntu", win);

        // then
        assertThat(learned.of("Ubuntu")).isEqualTo(win);
        assertThat(learned.knows("Ubuntu")).isTrue();
        assertThat(learned.of("Debian")).isEqualTo(WslMount.DEFAULT);
        assertThat(WslMounts.DEFAULT.knows("Ubuntu")).isFalse();
    }

    @Test
    @DisplayName("uniform 은 모든 배포판에 같은 마운트를 쓴다")
    void uniformAppliesToEveryDistro() {
        // when
        WslMounts root = WslMounts.uniform(new WslMount("/"));

        // then
        assertThat(root.of("A")).isEqualTo(new WslMount("/"));
        assertThat(root.of("B")).isEqualTo(new WslMount("/"));
    }

    @Test
    @DisplayName("배포판 표는 방어 복사한다")
    void copiesTheMap() {
        // given
        Map<String, WslMount> source = new HashMap<>(Map.of("A", new WslMount("/win/")));
        WslMounts mounts = new WslMounts(source, WslMount.DEFAULT);

        // when
        source.put("A", WslMount.DEFAULT);

        // then
        assertThat(mounts.of("A")).isEqualTo(new WslMount("/win/"));
    }
}
