package tanager.ui.pane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tanager.ui.pane.PaneFixtures.CMD;
import static tanager.ui.pane.PaneFixtures.UBUNTU;

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
import tanager.domain.history.ShellEnvironment;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ProfileResolution;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;

class ShellPickerTest {
    private static final ShellProfile A = shell("wsl:A", ShellKind.BASH, new ShellEnvironment.Wsl("A"));
    private static final ShellProfile B = shell("wsl:B", ShellKind.BASH, new ShellEnvironment.Wsl("B"));
    private static final ShellProfile PS =
            shell("powershell", ShellKind.WINDOWS_POWERSHELL, new ShellEnvironment.Windows());
    private static final ShellProfile CMD4 = shell("cmd", ShellKind.CMD, new ShellEnvironment.Windows());
    private static final List<ShellProfile> FOUR = List.of(A, B, PS, CMD4);

    private static ShellProfile shell(String id, ShellKind kind, ShellEnvironment env) {
        return new ShellProfile(
                new ProfileId(id), id, kind, env, new LaunchTemplate(id + ".exe", List.of()), IntegrationMode.NONE);
    }

    private static ShellPicker startup() {
        return ShellPicker.atStartup(new ProfileResolution.Choose(FOUR, Optional.empty()));
    }

    private static ShellPicker after(ShellPicker picker, PickerKey... keys) {
        ShellPicker current = picker;
        for (PickerKey key : keys) {
            current = ((PickerStep.Stay) current.on(key)).picker();
        }
        return current;
    }

    @Test
    @DisplayName("시작 때 선택 화면은 첫 항목을 고르고 기억이 켜져 있다")
    void startupDefaults() {
        // when
        ShellPicker picker = startup();
        // then
        assertThat(picker.selected()).isZero();
        assertThat(picker.remember()).isTrue();
        assertThat(picker.missing()).isEmpty();
        assertThat(picker.choices()).containsExactlyElementsOf(FOUR);
    }

    @Test
    @DisplayName("사라진 기본 셸 id 를 선택 화면이 간직한다")
    void startupKeepsMissing() {
        // when
        ShellPicker picker =
                ShellPicker.atStartup(new ProfileResolution.Choose(FOUR, Optional.of(new ProfileId("wsl:Gone"))));
        // then
        assertThat(picker.missing()).contains(new ProfileId("wsl:Gone"));
    }

    @Test
    @DisplayName("아래 두 번 위 한 번 누르면 선택이 둘째 항목이다")
    void moveUpAndDown() {
        // when
        ShellPicker picker = after(startup(), new PickerKey.Move(1), new PickerKey.Move(1), new PickerKey.Move(-1));
        // then
        assertThat(picker.selected()).isEqualTo(1);
    }

    @Test
    @DisplayName("이동은 양 끝에서 멈추고 돌지 않는다")
    void moveStopsAtEnds() {
        // then
        assertThat(after(startup(), new PickerKey.Move(-1)).selected()).isZero();
        ShellPicker last = after(startup(), new PickerKey.Move(1), new PickerKey.Move(1), new PickerKey.Move(1));
        assertThat(last.selected()).isEqualTo(3);
        assertThat(after(last, new PickerKey.Move(1)).selected()).isEqualTo(3);
    }

    @Test
    @DisplayName("숫자를 누르면 그 번호의 셸을 바로 고른다")
    void digitPicks() {
        // when
        PickerStep step = startup().on(new PickerKey.Digit(2));
        // then
        assertThat(step).isEqualTo(new PickerStep.Picked(new Attempt.Chosen(B, true)));
    }

    @Test
    @DisplayName("범위 밖 숫자는 아무 일도 하지 않는다")
    void digitOutOfRangeStays() {
        // given
        ShellPicker picker = startup();
        // then
        assertThat(picker.on(new PickerKey.Digit(9))).isEqualTo(new PickerStep.Stay(picker));
    }

    @Test
    @DisplayName("행을 클릭하면 그 셸을 고르고 범위 밖 클릭은 무시한다")
    void clickPicks() {
        // given
        ShellPicker picker = startup();
        // then
        assertThat(picker.on(new PickerKey.Click(3))).isEqualTo(new PickerStep.Picked(new Attempt.Chosen(CMD4, true)));
        assertThat(picker.on(new PickerKey.Click(4))).isEqualTo(new PickerStep.Stay(picker));
    }

    @Test
    @DisplayName("기억을 끄고 고르면 기억 없는 선택이 된다")
    void toggleRememberThenPick() {
        // when
        ShellPicker toggled = after(startup(), new PickerKey.ToggleRemember());
        // then
        assertThat(toggled.remember()).isFalse();
        assertThat(toggled.pickSelected()).isEqualTo(new Attempt.Chosen(A, false));
    }

    @Test
    @DisplayName("이동한 뒤 Enter 는 선택된 셸을 고른다")
    void pickSelectedAfterMove() {
        // when
        ShellPicker moved = after(startup(), new PickerKey.Move(1));
        // then
        assertThat(moved.pickSelected()).isEqualTo(new Attempt.Chosen(B, true));
    }

    @Test
    @DisplayName("Tab 으로 다시 열면 현재 셸을 선택하고 기억은 꺼져 있다")
    void reopenedSelectsCurrentAndRememberIsOff() {
        // when
        ShellPicker picker = ShellPicker.reopened(FOUR, Optional.of(new ProfileId("cmd")));
        // then
        assertThat(picker.selected()).isEqualTo(3);
        assertThat(picker.remember()).isFalse();
        assertThat(picker.missing()).isEmpty();
    }

    @Test
    @DisplayName("현재 셸이 목록에 없거나 모르면 첫 항목을 선택한다")
    void reopenedFallsBackToFirst() {
        // then
        assertThat(ShellPicker.reopened(FOUR, Optional.of(new ProfileId("wsl:Gone")))
                        .selected())
                .isZero();
        assertThat(ShellPicker.reopened(FOUR, Optional.empty()).selected()).isZero();
    }

    @Test
    @DisplayName("목록이 비었거나 선택 위치가 범위 밖이거나 사라진 id 가 목록에 있으면 만들 수 없다")
    void invariants() {
        assertThatThrownBy(() -> new ShellPicker(List.of(), 0, true, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShellPicker(FOUR, -1, true, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShellPicker(FOUR, 4, true, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShellPicker(FOUR, 0, true, Optional.of(new ProfileId("cmd"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("입력 값은 범위를 벗어나면 만들 수 없다")
    void keyInvariants() {
        assertThatThrownBy(() -> new PickerKey.Move(2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PickerKey.Digit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PickerKey.Digit(10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PickerKey.Click(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Provide
    Arbitrary<List<PickerKey>> keySequences() {
        Arbitrary<PickerKey> key = Arbitraries.oneOf(
                Arbitraries.of(-1, 1).map(PickerKey.Move::new),
                Arbitraries.of(new PickerKey.ToggleRemember()),
                Arbitraries.integers().between(1, 9).map(PickerKey.Digit::new),
                Arbitraries.integers().between(0, 12).map(PickerKey.Click::new));
        return key.list().ofMaxSize(30);
    }

    @Property
    @Label("어떤 입력 순서에서도 선택 위치는 목록 범위 안이고 목록은 바뀌지 않는다")
    void selectionStaysInRange(@ForAll("keySequences") List<PickerKey> keys) {
        // given
        ShellPicker current =
                ShellPicker.atStartup(new ProfileResolution.Choose(List.of(CMD, UBUNTU, A), Optional.empty()));

        // when
        for (PickerKey key : keys) {
            if (current.on(key) instanceof PickerStep.Stay stay) {
                current = stay.picker();
            } else {
                break;
            }
            // then
            assertThat(current.selected()).isBetween(0, 2);
            assertThat(current.choices()).containsExactly(CMD, UBUNTU, A);
        }
    }
}
