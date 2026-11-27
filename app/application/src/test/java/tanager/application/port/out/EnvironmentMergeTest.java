package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.LaunchCommand.EnvNames;

/** {@link LaunchCommand#environmentOver} — OS 분기 없는 순수 병합(M3-01). */
class EnvironmentMergeTest {

    private static LaunchCommand command(EnvNames names, Map<String, String> environment) {
        return new LaunchCommand(List.of("sh"), Optional.empty(), environment, names);
    }

    @Test
    @DisplayName("대소문자 무시: 상속한 Path 는 지워지고 덮어쓴 PATH 하나만 남는다")
    void caseInsensitiveDropsDifferentlyCasedInheritedKey() {
        Map<String, String> merged =
                command(EnvNames.CASE_INSENSITIVE, Map.of("PATH", "B")).environmentOver(Map.of("Path", "A"));

        assertThat(merged).containsEntry("PATH", "B").doesNotContainKey("Path");
        assertThat(merged.keySet().stream().filter(k -> k.equalsIgnoreCase("path")))
                .hasSize(1);
    }

    @Test
    @DisplayName("대소문자 무시: 상속에 Path 와 PATH 가 함께 있어도 path 로 덮으면 하나만 남는다")
    void caseInsensitiveDropsEveryDifferentlyCasedInheritedKey() {
        // given
        Map<String, String> inherited = Map.of("Path", "A", "PATH", "B");
        // when
        Map<String, String> merged =
                command(EnvNames.CASE_INSENSITIVE, Map.of("path", "C")).environmentOver(inherited);
        // then
        assertThat(merged.keySet().stream().filter(k -> k.equalsIgnoreCase("path")))
                .containsExactly("path");
        assertThat(merged).containsEntry("path", "C");
    }

    @Test
    @DisplayName("대소문자 비교는 Windows 처럼 글자마다 단순 대문자로 한다 — İ/i · 켈빈 K/K · ß/SS 는 서로 다른 이름이고 검사와 병합이 같게 판정한다")
    void caseFoldingIsPerCharacterSimpleUppercase() {
        for (Map<String, String> pair : List.of(
                Map.of("\u0130", "1", "i", "2"), Map.of("\u212A", "1", "K", "2"), Map.of("\u00DF", "1", "SS", "2"))) {
            // when: 생성자가 받아 주고
            LaunchCommand command = command(EnvNames.CASE_INSENSITIVE, pair);
            // then: 병합도 둘 다 남긴다
            Map<String, String> merged = command.environmentOver(Map.of());
            pair.forEach((name, value) -> assertThat(merged).as(name).containsEntry(name, value));
        }
    }

    @Test
    @DisplayName("대소문자 구분: Path 와 PATH 가 둘 다 남는다")
    void caseSensitiveKeepsBoth() {
        Map<String, String> merged =
                command(EnvNames.CASE_SENSITIVE, Map.of("PATH", "B")).environmentOver(Map.of("Path", "A"));

        assertThat(merged).containsEntry("Path", "A").containsEntry("PATH", "B");
    }

    @Test
    @DisplayName("상속 환경에서 덮어쓰지 않은 변수는 그대로 남는다")
    void untouchedInheritedVariablesSurvive() {
        Map<String, String> merged = command(EnvNames.CASE_INSENSITIVE, Map.of("A", "1"))
                .environmentOver(Map.of("HOME", "/h", "windir", "C:\\Windows"));

        assertThat(merged).containsEntry("HOME", "/h").containsEntry("windir", "C:\\Windows");
    }

    @Test
    @DisplayName("상속에 TERM=dumb 이 있어도 기본값 xterm-256color 가 이긴다")
    void termDefaultBeatsInherited() {
        Map<String, String> merged = command(EnvNames.CASE_SENSITIVE, Map.of()).environmentOver(Map.of("TERM", "dumb"));

        assertThat(merged).containsEntry("TERM", "xterm-256color");
    }

    @Test
    @DisplayName("상속에 TERM 이 없어도 기본값 xterm-256color 가 들어간다")
    void termDefaultIsAddedWhenMissing() {
        Map<String, String> merged = command(EnvNames.CASE_SENSITIVE, Map.of()).environmentOver(Map.of());

        assertThat(merged).containsEntry("TERM", "xterm-256color");
    }

    @Test
    @DisplayName("environment 에 TERM 이 있으면 그 값이 기본값을 이긴다")
    void explicitTermBeatsDefault() {
        Map<String, String> merged =
                command(EnvNames.CASE_SENSITIVE, Map.of("TERM", "vt100")).environmentOver(Map.of("TERM", "dumb"));

        assertThat(merged).containsEntry("TERM", "vt100");
    }

    @Test
    @DisplayName("대소문자 무시: 상속한 Term 도 TERM 하나로 합쳐진다")
    void caseInsensitiveTermIsSingle() {
        Map<String, String> merged =
                command(EnvNames.CASE_INSENSITIVE, Map.of()).environmentOver(Map.of("Term", "dumb"));

        assertThat(merged).containsEntry("TERM", "xterm-256color").doesNotContainKey("Term");
    }

    @Test
    @DisplayName("상속 환경의 =C: 같은 특수 변수는 검증 없이 통과한다")
    void inheritedSpecialVariablesAreNotValidated() {
        Map<String, String> merged =
                command(EnvNames.CASE_INSENSITIVE, Map.of()).environmentOver(Map.of("=C:", "C:\\work"));

        assertThat(merged).containsEntry("=C:", "C:\\work");
    }

    @Test
    @DisplayName("입력 상속 맵은 바뀌지 않는다")
    void inheritedMapIsNotMutated() {
        Map<String, String> inherited = new HashMap<>(Map.of("Path", "A"));

        command(EnvNames.CASE_INSENSITIVE, Map.of("PATH", "B")).environmentOver(inherited);

        assertThat(inherited).containsExactly(Map.entry("Path", "A"));
    }

    @Provide
    Arbitrary<Map<String, String>> variables() {
        Arbitrary<String> names = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(4);
        Arbitrary<String> values = Arbitraries.strings().alpha().ofMaxLength(4);
        return Arbitraries.maps(names, values).ofMaxSize(6);
    }

    // 대소문자 무시 환경의 environment 는 대소문자만 다른 이름을 가질 수 없다(생성자가 거부한다)
    @Provide
    Arbitrary<Map<String, String>> caseDistinctVariables() {
        return variables()
                .filter(m ->
                        m.keySet().stream().map(String::toUpperCase).distinct().count() == m.size());
    }

    @Test
    @DisplayName("대소문자 무시 환경에서 environment 에 대소문자만 다른 이름이 있으면 IAE — 어느 쪽이 이길지 정할 수 없다")
    void caseInsensitiveRejectsCollidingNames() {
        // when / then
        assertThatThrownBy(() -> command(EnvNames.CASE_INSENSITIVE, Map.of("PATH", "a", "Path", "b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(command(EnvNames.CASE_SENSITIVE, Map.of("PATH", "a", "Path", "b"))
                        .environment())
                .hasSize(2);
    }

    @Property
    @Label("대소문자 무시 병합: environment 의 이름마다 결과에 대소문자 무시로 맞는 키가 정확히 하나이고 그 값이며, 덮지 않은 상속 키는 그대로다")
    void caseInsensitiveResultHasExactlyOneKeyPerOverride(
            @ForAll("variables") Map<String, String> inherited,
            @ForAll("caseDistinctVariables") Map<String, String> environment) {
        // when
        Map<String, String> merged =
                command(EnvNames.CASE_INSENSITIVE, environment).environmentOver(inherited);
        // then
        environment.forEach((name, value) -> {
            List<String> same = merged.keySet().stream()
                    .filter(k -> k.toUpperCase(Locale.ROOT).equals(name.toUpperCase(Locale.ROOT)))
                    .toList();
            assertThat(same).as(name).containsExactly(name);
            assertThat(merged).containsEntry(name, value);
        });
        inherited.forEach((name, value) -> {
            boolean overridden = name.equalsIgnoreCase("TERM")
                    || environment.keySet().stream().anyMatch(n -> n.equalsIgnoreCase(name));
            if (!overridden) {
                assertThat(merged).containsEntry(name, value);
            }
        });
    }

    @Property
    @Label("대소문자 구분 병합은 상속 키를 environment 와 이름이 정확히 같을 때만 덮어쓴다")
    void caseSensitiveOverridesOnlyExactNames(
            @ForAll("variables") Map<String, String> inherited, @ForAll("variables") Map<String, String> environment) {
        Map<String, String> merged =
                command(EnvNames.CASE_SENSITIVE, environment).environmentOver(inherited);

        environment.forEach((name, value) -> assertThat(merged).containsEntry(name, value));
        inherited.forEach((name, value) -> {
            if (!environment.containsKey(name) && !name.equals("TERM")) {
                assertThat(merged).containsEntry(name, value);
            }
        });
    }
}
