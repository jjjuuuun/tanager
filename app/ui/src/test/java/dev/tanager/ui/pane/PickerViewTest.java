package dev.tanager.ui.pane;

import static dev.tanager.ui.pane.PaneFixtures.CMD;
import static dev.tanager.ui.pane.PaneFixtures.UBUNTU;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ProfileResolution;
import dev.tanager.domain.session.ShellProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PickerViewTest {
    private static PaneState.Picking picking(ShellPicker picker) {
        return new PaneState.Picking(picker, new PickerExit.CloseWindow());
    }

    private static ShellPicker startup(List<ShellProfile> shells) {
        return ShellPicker.atStartup(new ProfileResolution.Choose(shells, Optional.empty()));
    }

    @Test
    @DisplayName("행은 탐지 순서 그대로이고 번호는 1부터 붙는다")
    void rowsFollowDetectionOrder() {
        // when
        PickerView view = PickerView.of(picking(startup(List.of(UBUNTU, CMD))));

        // then
        assertThat(view.rows()).extracting(PickerView.Row::name).containsExactly("Ubuntu", "cmd");
        assertThat(view.rows()).extracting(PickerView.Row::key).containsExactly("1", "2");
    }

    @Test
    @DisplayName("열 번째 셸부터는 번호 글자가 비어 있다")
    void tenthRowHasNoKey() {
        // given
        List<ShellProfile> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(new ShellProfile(
                    new ProfileId("p" + i), "p" + i, CMD.kind(), CMD.env(), CMD.launch(), CMD.integration()));
        }

        // when
        PickerView view = PickerView.of(picking(startup(ten)));

        // then
        assertThat(view.rows().get(8).key()).isEqualTo("9");
        assertThat(view.rows().get(9).key()).isEmpty();
    }

    @Test
    @DisplayName("선택된 행만 selected 다")
    void onlySelectedRowIsMarked() {
        // given
        ShellPicker picker = ((PickerStep.Stay) startup(List.of(UBUNTU, CMD)).on(new PickerKey.Move(1))).picker();

        // when
        PickerView view = PickerView.of(picking(picker));

        // then
        assertThat(view.rows()).extracting(PickerView.Row::selected).containsExactly(false, true);
    }

    @Test
    @DisplayName("설명은 환경 · 셸 형식이다")
    void detailText() {
        // when
        PickerView view = PickerView.of(picking(startup(List.of(UBUNTU, CMD))));

        // then
        assertThat(view.rows().get(0).detail()).isEqualTo("WSL · Ubuntu · bash");
        assertThat(view.rows().get(1).detail()).isEqualTo("Windows · cmd");
    }

    @Test
    @DisplayName("기억 라벨은 고정 문구이고 체크 상태는 선택 화면 값 그대로다")
    void rememberLabelAndValue() {
        // given
        ShellPicker on = startup(List.of(CMD));
        ShellPicker off = ((PickerStep.Stay) on.on(new PickerKey.ToggleRemember())).picker();

        // then
        assertThat(PickerView.of(picking(on)).rememberLabel()).isEqualTo("다음부터 이 셸로 바로 열기");
        assertThat(PickerView.of(picking(on)).remember()).isTrue();
        assertThat(PickerView.of(picking(off)).remember()).isFalse();
    }
}
