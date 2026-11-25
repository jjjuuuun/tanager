package dev.tanager.domain.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.domain.history.ShellEnvironment;
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

class DefaultProfileTest {
    private static final ShellProfile WSL_A = profile("wsl:A", ShellKind.BASH, new ShellEnvironment.Wsl("A"));
    private static final ShellProfile WSL_B = profile("wsl:B", ShellKind.BASH, new ShellEnvironment.Wsl("B"));
    private static final ShellProfile POWERSHELL =
            profile("powershell", ShellKind.WINDOWS_POWERSHELL, new ShellEnvironment.Windows());
    private static final ShellProfile CMD = profile("cmd", ShellKind.CMD, new ShellEnvironment.Windows());

    private static ShellProfile profile(String id, ShellKind kind, ShellEnvironment env) {
        return new ShellProfile(
                new ProfileId(id), id, kind, env, new LaunchTemplate("shell", List.of()), IntegrationMode.NONE);
    }

    @Test
    @DisplayName("선호 프로파일이 목록에 있으면 그것을 고르고 대체 표시는 없다")
    void choosesPreferredWhenAvailable() {
        // when
        Optional<ProfileChoice> choice =
                DefaultProfile.choose(Optional.of(new ProfileId("powershell")), List.of(WSL_A, WSL_B, POWERSHELL, CMD));
        // then
        assertThat(choice).contains(new ProfileChoice(POWERSHELL, Optional.empty()));
    }

    @Test
    @DisplayName("선호가 없으면 첫 WSL 프로파일을 고른다")
    void choosesFirstWslWithoutPreference() {
        // when
        Optional<ProfileChoice> choice =
                DefaultProfile.choose(Optional.empty(), List.of(POWERSHELL, WSL_A, WSL_B, CMD));
        // then
        assertThat(choice).contains(new ProfileChoice(WSL_A, Optional.empty()));
    }

    @Test
    @DisplayName("선호한 프로파일이 사라졌으면 첫 WSL 로 대체하고 사라진 id 를 알린다")
    void replacesMissingPreference() {
        // when
        Optional<ProfileChoice> choice =
                DefaultProfile.choose(Optional.of(new ProfileId("wsl:Gone")), List.of(WSL_A, WSL_B, POWERSHELL, CMD));
        // then
        assertThat(choice).contains(new ProfileChoice(WSL_A, Optional.of(new ProfileId("wsl:Gone"))));
    }

    @Test
    @DisplayName("WSL 이 없으면 목록의 첫 항목을 고른다")
    void choosesFirstWhenNoWsl() {
        // when
        Optional<ProfileChoice> choice = DefaultProfile.choose(Optional.empty(), List.of(POWERSHELL, CMD));
        // then
        assertThat(choice).contains(new ProfileChoice(POWERSHELL, Optional.empty()));
    }

    @Test
    @DisplayName("목록이 비면 고를 것이 없다")
    void emptyListChoosesNothing() {
        assertThat(DefaultProfile.choose(Optional.empty(), List.of())).isEmpty();
        assertThat(DefaultProfile.choose(Optional.of(new ProfileId("x")), List.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("ProfileChoice 는 대체한 id 가 고른 프로파일 id 와 같으면 거부한다")
    void choiceRejectsReplacedEqualToChosen() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ProfileChoice(WSL_A, Optional.of(new ProfileId("wsl:A"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Provide
    Arbitrary<List<ShellProfile>> profiles() {
        return Arbitraries.of(WSL_A, WSL_B, POWERSHELL, CMD)
                .list()
                .uniqueElements()
                .ofMaxSize(4);
    }

    @Provide
    Arbitrary<Optional<ProfileId>> preferred() {
        return Arbitraries.of("wsl:A", "wsl:B", "powershell", "cmd", "wsl:Gone")
                .map(ProfileId::new)
                .optional()
                .map(o -> o);
    }

    @Property
    @Label("목록이 비지 않으면 항상 목록 안의 프로파일을 고르고, 선호가 목록에 있으면 반드시 그것이다")
    void alwaysChoosesFromListAndHonorsPreference(
            @ForAll("profiles") List<ShellProfile> available, @ForAll("preferred") Optional<ProfileId> preferred) {
        // when
        Optional<ProfileChoice> choice = DefaultProfile.choose(preferred, available);
        // then
        assertThat(choice.isPresent()).isEqualTo(!available.isEmpty());
        choice.ifPresent(c -> {
            assertThat(available).contains(c.profile());
            Optional<ShellProfile> wanted = preferred.flatMap(
                    id -> available.stream().filter(p -> p.id().equals(id)).findFirst());
            if (wanted.isPresent()) {
                assertThat(c.profile()).isEqualTo(wanted.get());
                assertThat(c.replaced()).isEmpty();
            } else {
                assertThat(c.replaced()).isEqualTo(preferred);
            }
        });
    }
}
