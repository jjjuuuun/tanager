package dev.tanager.domain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tanager.domain.history.ShellEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProfileResolutionTest {
    private static final ShellProfile WSL_A = profile("wsl:A", ShellKind.BASH, new ShellEnvironment.Wsl("A"));
    private static final ShellProfile WSL_B = profile("wsl:B", ShellKind.BASH, new ShellEnvironment.Wsl("B"));
    private static final ShellProfile POWERSHELL =
            profile("powershell", ShellKind.WINDOWS_POWERSHELL, new ShellEnvironment.Windows());
    private static final ShellProfile CMD = profile("cmd", ShellKind.CMD, new ShellEnvironment.Windows());
    private static final List<ShellProfile> ALL = List.of(WSL_A, WSL_B, POWERSHELL, CMD);

    private static ShellProfile profile(String id, ShellKind kind, ShellEnvironment env) {
        return new ShellProfile(
                new ProfileId(id), id, kind, env, new LaunchTemplate("shell", List.of()), IntegrationMode.NONE);
    }

    @Test
    @DisplayName("원하는 프로파일이 목록에 있으면 Found 다")
    void foundWhenWantedIsAvailable() {
        // when
        ProfileResolution result = ProfileResolution.resolve(Optional.of(new ProfileId("powershell")), ALL);
        // then
        assertThat(result).isEqualTo(new ProfileResolution.Found(POWERSHELL));
    }

    @Test
    @DisplayName("원하는 것이 없으면 첫 WSL 을 고르지 않고 목록 그대로 Choose 다")
    void chooseWithoutWanted() {
        // when
        ProfileResolution result = ProfileResolution.resolve(Optional.empty(), ALL);
        // then
        assertThat(result).isEqualTo(new ProfileResolution.Choose(ALL, Optional.empty()));
    }

    @Test
    @DisplayName("원하는 프로파일이 목록에 없으면 사라진 id 를 담아 Choose 다")
    void chooseWithMissing() {
        // when
        ProfileResolution result = ProfileResolution.resolve(Optional.of(new ProfileId("wsl:Gone")), ALL);
        // then
        assertThat(result).isEqualTo(new ProfileResolution.Choose(ALL, Optional.of(new ProfileId("wsl:Gone"))));
    }

    @Test
    @DisplayName("WSL 이 첫째가 아니어도 탐지 순서를 그대로 둔다")
    void keepsDetectionOrder() {
        // when
        ProfileResolution result = ProfileResolution.resolve(Optional.empty(), List.of(POWERSHELL, WSL_A, CMD));
        // then
        assertThat(result)
                .isInstanceOfSatisfying(
                        ProfileResolution.Choose.class,
                        choose -> assertThat(choose.choices()).containsExactly(POWERSHELL, WSL_A, CMD));
    }

    @Test
    @DisplayName("목록이 비면 원하는 것이 있든 없든 NoShell 이다")
    void noShellWhenEmpty() {
        // then
        assertThat(ProfileResolution.resolve(Optional.empty(), List.of())).isEqualTo(new ProfileResolution.NoShell());
        assertThat(ProfileResolution.resolve(Optional.of(new ProfileId("x")), List.of()))
                .isEqualTo(new ProfileResolution.NoShell());
    }

    @Test
    @DisplayName("Choose 는 빈 목록을 받지 않는다")
    void chooseRejectsEmptyChoices() {
        assertThatThrownBy(() -> new ProfileResolution.Choose(List.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Choose 는 사라진 id 가 목록에 있는 것을 받지 않는다")
    void chooseRejectsMissingThatIsListed() {
        assertThatThrownBy(() -> new ProfileResolution.Choose(List.of(WSL_A), Optional.of(new ProfileId("wsl:A"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Choose 는 목록을 방어 복사한다")
    void chooseCopiesChoices() {
        // given
        List<ShellProfile> mutable = new ArrayList<>(List.of(WSL_A, CMD));
        ProfileResolution.Choose choose = new ProfileResolution.Choose(mutable, Optional.empty());
        // when
        mutable.clear();
        // then
        assertThat(choose.choices()).containsExactly(WSL_A, CMD);
    }

    @Provide
    Arbitrary<List<ShellProfile>> subsets() {
        return Arbitraries.subsetOf(ALL)
                .map(set -> ALL.stream().filter(set::contains).toList());
    }

    @Provide
    Arbitrary<Optional<ProfileId>> wanted() {
        return Arbitraries.of("wsl:A", "wsl:B", "powershell", "cmd", "wsl:Gone", "")
                .map(id -> id.isEmpty() ? Optional.<ProfileId>empty() : Optional.of(new ProfileId(id)));
    }

    @Property
    @Label("resolve 는 목록 · 원하는 것의 조합에 따라 NoShell · Found · Choose 로 갈린다")
    void resolveClassifiesAllInputs(
            @ForAll("subsets") List<ShellProfile> available, @ForAll("wanted") Optional<ProfileId> wanted) {
        // when
        ProfileResolution result = ProfileResolution.resolve(wanted, available);

        // then
        Optional<ShellProfile> match = available.stream()
                .filter(p -> wanted.filter(p.id()::equals).isPresent())
                .findFirst();
        if (available.isEmpty()) {
            assertThat(result).isEqualTo(new ProfileResolution.NoShell());
        } else if (match.isPresent()) {
            assertThat(result).isEqualTo(new ProfileResolution.Found(match.get()));
        } else {
            assertThat(result).isEqualTo(new ProfileResolution.Choose(available, wanted));
        }
    }
}
