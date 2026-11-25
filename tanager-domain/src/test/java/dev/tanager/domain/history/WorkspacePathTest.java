package dev.tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tanager.domain.shared.Result;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspacePathTest {

    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("Ubuntu-24.04");

    private static String norm(ShellEnvironment env, String raw) {
        Result<WorkspacePath, PathError> r = WorkspacePath.of(env, raw);
        assertThat(r.isOk()).as("of(%s, %s) 성공", env, raw).isTrue();
        return r.value().orElseThrow().path();
    }

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    @Test
    @DisplayName("ShellEnvironment.Wsl 은 빈 배포판 이름을 거부한다")
    void wslRejectsBlankDistro() {
        // given / when / then
        assertThatThrownBy(() -> new ShellEnvironment.Wsl("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShellEnvironment.Wsl("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Windows 경로의 드라이브 문자는 대문자로 바뀐다")
    void windowsUppercasesDriveLetter() {
        // given
        String raw = "c:\\work";
        // when
        String path = norm(WIN, raw);
        // then
        assertThat(path).isEqualTo("C:\\work");
    }

    @Test
    @DisplayName("Windows 경로는 슬래시를 역슬래시로 바꾸고 중복·끝 구분자를 제거한다")
    void windowsNormalizesSeparators() {
        // given
        String raw = "C:/Work//src/";
        // when
        String path = norm(WIN, raw);
        // then
        assertThat(path).isEqualTo("C:\\Work\\src");
    }

    @ParameterizedTest
    @ValueSource(strings = {"C:", "C:\\", "c:/"})
    @DisplayName("Windows 드라이브 루트는 끝 역슬래시를 유지한다")
    void windowsDriveRootKeepsTrailingBackslash(String raw) {
        // given / when
        String path = norm(WIN, raw);
        // then
        assertThat(path).isEqualTo("C:\\");
    }

    @ParameterizedTest
    @CsvSource({"C:\\a\\.\\b\\..\\c,C:\\a\\c", "C:\\..\\x,C:\\x"})
    @DisplayName("Windows 경로의 . 과 .. 을 해소하며 루트 위로는 올라가지 않는다")
    void windowsResolvesDots(String raw, String expected) {
        // given / when
        String path = norm(WIN, raw);
        // then
        assertThat(path).isEqualTo(expected);
    }

    @Test
    @DisplayName("UNC 경로는 앞의 두 역슬래시를 유지하고 나머지를 정규화한다")
    void uncKeepsLeadingDoubleBackslash() {
        // given
        String raw = "\\\\wsl.localhost\\Ubuntu-24.04\\home\\me";
        // when
        String path = norm(WIN, raw);
        // then
        assertThat(path).isEqualTo("\\\\wsl.localhost\\Ubuntu-24.04\\home\\me");
    }

    @Test
    @DisplayName("UNC 경로도 중복 구분자와 끝 구분자를 정규화한다")
    void uncNormalizesRest() {
        // given
        String raw = "\\\\wsl.localhost\\Ubuntu-24.04\\\\home/me\\";
        // when
        String path = norm(WIN, raw);
        // then
        assertThat(path).isEqualTo("\\\\wsl.localhost\\Ubuntu-24.04\\home\\me");
    }

    @Test
    @DisplayName("Windows 경로는 대소문자만 달라도 같고 path() 는 입력의 대소문자를 보존한다")
    void windowsEqualityIgnoresCaseButPathKeepsInput() {
        // given
        WorkspacePath a = path(WIN, "C:\\Work");
        WorkspacePath b = path(WIN, "c:\\work\\");
        // when / then
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.path()).isEqualTo("C:\\Work");
        assertThat(b.path()).isEqualTo("C:\\work");
    }

    @ParameterizedTest
    @ValueSource(strings = {"work\\src", "\\work", "work", "~\\x"})
    @DisplayName("Windows 에서 드라이브 없는 경로는 NotAbsolute 이다")
    void windowsRelativeIsNotAbsolute(String raw) {
        // given / when
        Result<WorkspacePath, PathError> r = WorkspacePath.of(WIN, raw);
        // then
        assertThat(r.error()).contains(new PathError.NotAbsolute(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   \t"})
    @DisplayName("빈 문자열과 공백은 Blank 이다")
    void blankIsRejected(String raw) {
        // given / when / then
        assertThat(WorkspacePath.of(WIN, raw).error()).contains(new PathError.Blank());
        assertThat(WorkspacePath.of(WSL, raw).error()).contains(new PathError.Blank());
    }

    @ParameterizedTest
    @CsvSource({"/home//me/proj/,/home/me/proj", "/,/", "//,/"})
    @DisplayName("WSL 경로는 중복·끝 구분자를 제거하고 루트는 /로 유지한다")
    void wslNormalizesSeparators(String raw, String expected) {
        // given / when
        String path = norm(WSL, raw);
        // then
        assertThat(path).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"/home/me/./a/../b,/home/me/b", "/../x,/x"})
    @DisplayName("WSL 경로의 . 과 .. 을 해소하며 루트 위로는 올라가지 않는다")
    void wslResolvesDots(String raw, String expected) {
        // given / when
        String path = norm(WSL, raw);
        // then
        assertThat(path).isEqualTo(expected);
    }

    @Test
    @DisplayName("WSL 경로는 대소문자를 구분한다")
    void wslIsCaseSensitive() {
        // given
        WorkspacePath a = path(WSL, "/home/a/Proj");
        WorkspacePath b = path(WSL, "/home/a/proj");
        // when / then
        assertThat(a).isNotEqualTo(b);
    }

    @ParameterizedTest
    @ValueSource(strings = {"home/me", "~/proj", "./x"})
    @DisplayName("WSL 에서 /로 시작하지 않는 경로는 NotAbsolute 이다")
    void wslRelativeIsNotAbsolute(String raw) {
        // given / when
        Result<WorkspacePath, PathError> r = WorkspacePath.of(WSL, raw);
        // then
        assertThat(r.error()).contains(new PathError.NotAbsolute(raw));
    }

    @Test
    @DisplayName("같은 문자열이라도 배포판이 다르면 같지 않다")
    void differentDistrosAreNotEqual() {
        // given
        WorkspacePath ubuntu = path(new ShellEnvironment.Wsl("Ubuntu"), "/home/me");
        WorkspacePath debian = path(new ShellEnvironment.Wsl("Debian"), "/home/me");
        WorkspacePath same = path(new ShellEnvironment.Wsl("Ubuntu"), "/home/me/");
        // when / then
        assertThat(ubuntu).isNotEqualTo(debian);
        assertThat(ubuntu).isEqualTo(same).hasSameHashCodeAs(same);
    }

    @Test
    @DisplayName("env() 는 만들 때의 환경을 돌려주고 Windows 와 WSL 경로는 같지 않다")
    void environmentIsPartOfIdentity() {
        // given
        WorkspacePath wsl = path(WSL, "/x");
        WorkspacePath win = path(WIN, "C:\\x");
        // when / then
        assertThat(wsl.env()).isEqualTo(WSL);
        assertThat(win.env()).isEqualTo(WIN);
        assertThat(wsl).isNotEqualTo(win);
    }

    @Provide
    Arbitrary<String> windowsPaths() {
        Arbitrary<String> seg = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(6);
        Arbitrary<String> mid = Arbitraries.of("\\", "/", "\\\\", "\\.\\", "/./");
        Arbitrary<String> drive = Arbitraries.chars().range('a', 'z').map(c -> c + ":");
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMaxLength(0)
                .flatMap(x -> drive.flatMap(d -> seg.list()
                        .ofMinSize(0)
                        .ofMaxSize(4)
                        .flatMap(segs -> mid.list()
                                .ofSize(segs.size() + 1)
                                .flatMap(seps -> Arbitraries.of("", "\\", "/").map(tail -> {
                                    StringBuilder sb = new StringBuilder(d);
                                    for (int i = 0; i < segs.size(); i++) {
                                        sb.append(seps.get(i)).append(segs.get(i));
                                    }
                                    return sb.append(segs.isEmpty() ? "" : tail).toString();
                                })))));
    }

    @Provide
    Arbitrary<String> wslPaths() {
        Arbitrary<String> seg = Arbitraries.of(".", "..", "a", "Bc", "proj", "x1");
        Arbitrary<String> sep = Arbitraries.of("/", "//");
        return seg.list()
                .ofMinSize(0)
                .ofMaxSize(6)
                .flatMap(segs -> sep.list()
                        .ofSize(segs.size() + 1)
                        .flatMap(seps -> Arbitraries.of("", "/").map(tail -> {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < segs.size(); i++) {
                                sb.append(seps.get(i)).append(segs.get(i));
                            }
                            if (segs.isEmpty()) {
                                sb.append("/");
                            }
                            return sb.append(tail).toString();
                        })));
    }

    @Property
    @Label("Windows 경로 정규화는 멱등이다")
    void windowsNormalizationIsIdempotent(@ForAll("windowsPaths") String raw) {
        // given
        String once = norm(WIN, raw);
        // when
        String twice = norm(WIN, once);
        // then
        assertThat(twice).isEqualTo(once);
    }

    @Property
    @Label("WSL 경로 정규화는 멱등이다")
    void wslNormalizationIsIdempotent(@ForAll("wslPaths") String raw) {
        // given
        String once = norm(WSL, raw);
        // when
        String twice = norm(WSL, once);
        // then
        assertThat(twice).isEqualTo(once);
    }

    @Property
    @Label("대소문자만 다른 두 Windows 입력은 항상 같다")
    void windowsCaseVariantsAreEqual(@ForAll("windowsPaths") String raw, @ForAll boolean flip) {
        // given
        String other = flip ? raw.toUpperCase() : raw.toLowerCase();
        // when
        WorkspacePath a = path(WIN, raw);
        WorkspacePath b = path(WIN, other);
        // then
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
