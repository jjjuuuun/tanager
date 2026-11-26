package dev.tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkspacePathSegmentsTest {

    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("Ubuntu-24.04");

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    @Test
    @DisplayName("끝 구분자는 세그먼트를 만들지 않는다")
    void trailingSeparatorAddsNoSegment() {
        // given / when / then
        assertThat(path(WIN, "C:\\Work\\").segments()).containsExactly("Work");
        assertThat(path(WSL, "/a/b/").segments()).containsExactly("a", "b");
        assertThat(path(WIN, "\\\\h\\s\\").segments()).isEmpty();
    }

    @Test
    @DisplayName("Windows 의 parent 는 표기를 보존하고 대소문자만 다른 경로와 equals 다(드라이브 · UNC)")
    void windowsParentKeepsSpellingAndIgnoresCase() {
        // given
        WorkspacePath drive = path(WIN, "c:\\WORK\\Src");
        WorkspacePath unc = path(WIN, "\\\\HOST\\Share\\a");
        // when
        WorkspacePath driveParent = drive.parent().orElseThrow();
        WorkspacePath uncParent = unc.parent().orElseThrow();
        // then
        assertThat(driveParent.path()).isEqualTo("C:\\WORK");
        assertThat(driveParent).isEqualTo(path(WIN, "C:\\work"));
        assertThat(unc).isEqualTo(path(WIN, "\\\\host\\share\\a"));
        assertThat(uncParent)
                .isEqualTo(path(WIN, "\\\\host\\share"))
                .isEqualTo(unc.parent().orElseThrow());
    }

    @Test
    @DisplayName("WSL 경로의 루트는 '/' 이고 세그먼트는 루트 아래 이름들이다")
    void wslRootAndSegments() {
        // given
        WorkspacePath p = path(WSL, "/home/juny/proj");
        // when / then
        assertThat(p.root()).isEqualTo("/");
        assertThat(p.segments()).containsExactly("home", "juny", "proj");
    }

    @Test
    @DisplayName("Windows 드라이브 경로의 루트는 'C:\\' 이고 세그먼트는 그 아래다")
    void windowsDriveRootAndSegments() {
        // given
        WorkspacePath p = path(WIN, "c:/Work/Src");
        // when / then
        assertThat(p.root()).isEqualTo("C:\\");
        assertThat(p.segments()).containsExactly("Work", "Src");
    }

    @Test
    @DisplayName("UNC 경로의 루트는 '\\\\host\\share' 이다")
    void uncRootAndSegments() {
        // given
        WorkspacePath p = path(WIN, "\\\\srv\\share\\a\\b");
        // when / then
        assertThat(p.root()).isEqualTo("\\\\srv\\share");
        assertThat(p.segments()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("루트 자체의 세그먼트는 비어 있고 parent 는 empty 다")
    void rootHasNoSegmentsAndNoParent() {
        // given / when / then
        assertThat(path(WSL, "/").segments()).isEmpty();
        assertThat(path(WSL, "/").parent()).isEmpty();
        assertThat(path(WIN, "C:\\").segments()).isEmpty();
        assertThat(path(WIN, "C:\\").parent()).isEmpty();
        assertThat(path(WIN, "\\\\srv\\share").parent()).isEmpty();
    }

    @Test
    @DisplayName("parent 는 한 단계 위 경로이고 환경은 같다")
    void parentIsOneLevelUp() {
        // given / when / then
        assertThat(path(WSL, "/a/b").parent()).contains(path(WSL, "/a"));
        assertThat(path(WSL, "/a").parent()).contains(path(WSL, "/"));
        assertThat(path(WIN, "C:\\Work\\src").parent()).contains(path(WIN, "C:\\Work"));
        assertThat(path(WIN, "C:\\Work").parent()).contains(path(WIN, "C:\\"));
        assertThat(path(WIN, "\\\\srv\\share\\a").parent()).contains(path(WIN, "\\\\srv\\share"));
    }

    @Test
    @DisplayName("segments 는 수정할 수 없다")
    void segmentsAreUnmodifiable() {
        // given
        List<String> segments = path(WSL, "/a/b").segments();
        // when / then
        assertThatThrownBy(() -> segments.add("c")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Provide
    Arbitrary<String> wslPaths() {
        Arbitrary<String> seg = Arbitraries.of("a", "Bc", "proj", "x1", "..", ".");
        return seg.list().ofMinSize(0).ofMaxSize(6).map(l -> "/" + String.join("/", l));
    }

    @Property
    @Label("parent 를 끝까지 따라가면 루트에 닿고 단계 수는 세그먼트 수와 같다")
    void parentChainEndsAtRoot(@ForAll("wslPaths") String raw) {
        // given
        WorkspacePath p = path(WSL, raw);
        int expected = p.segments().size();
        // when
        int steps = 0;
        WorkspacePath cur = p;
        while (cur.parent().isPresent()) {
            cur = cur.parent().get();
            steps++;
        }
        // then
        assertThat(steps).isEqualTo(expected);
        assertThat(cur.path()).isEqualTo(p.root());
    }
}
