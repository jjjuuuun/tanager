package tanager.ui.pane;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ProfileResolution;
import tanager.domain.session.ShellProfile;

/**
 * 셸 선택 화면의 순수 상태(FR-SES-02).
 *
 * @param choices 고를 셸, 탐지 순서
 * @param selected 선택된 위치
 * @param remember "다음부터 이 셸로 바로 열기"
 * @param missing 기억했지만 목록에 없는 셸
 */
record ShellPicker(List<ShellProfile> choices, int selected, boolean remember, Optional<ProfileId> missing) {
    ShellPicker {
        choices = List.copyOf(choices);
        Objects.requireNonNull(missing, "missing");
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("고를 셸이 없다");
        }
        if (selected < 0 || selected >= choices.size()) {
            throw new IllegalArgumentException("선택 위치가 범위 밖이다: " + selected);
        }
        if (missing.isPresent() && indexOf(choices, missing.get()).isPresent()) {
            throw new IllegalArgumentException("사라진 셸이 목록에 있다: " + missing.get());
        }
    }

    /** 시작 때: 첫 항목 선택, 기억 켜짐. */
    static ShellPicker atStartup(ProfileResolution.Choose choice) {
        return new ShellPicker(choice.choices(), 0, true, choice.missing());
    }

    /**
     * Tab 으로 열 때: {@code current} 가 목록에 있으면 그 항목, 없으면 첫 항목을 선택한다. 한 번 바꿔 쓰는 것만으로 기본 셸이 바뀌지 않게
     * 기억은 꺼져 있다.
     */
    static ShellPicker reopened(List<ShellProfile> choices, Optional<ProfileId> current) {
        int selected = current.flatMap(id -> indexOf(choices, id)).orElse(0);
        return new ShellPicker(choices, selected, false, Optional.empty());
    }

    /** 입력 하나를 처리한다. 이동은 끝에서 멈춘다(돌지 않는다). 숫자 · 클릭이 범위 밖이면 그대로다. */
    PickerStep on(PickerKey key) {
        return switch (key) {
            case PickerKey.Move(int delta) -> stay(Math.clamp(selected + delta, 0, choices.size() - 1), remember);
            case PickerKey.Digit(int number) -> pickAt(number - 1);
            case PickerKey.Click(int index) -> pickAt(index);
            case PickerKey.ToggleRemember _ -> stay(selected, !remember);
        };
    }

    /** Enter: 선택된 항목을 고른다. */
    Attempt.Chosen pickSelected() {
        return new Attempt.Chosen(choices.get(selected), remember);
    }

    private PickerStep pickAt(int index) {
        if (index >= choices.size()) {
            return new PickerStep.Stay(this);
        }
        return new PickerStep.Picked(new Attempt.Chosen(choices.get(index), remember));
    }

    private PickerStep stay(int nextSelected, boolean nextRemember) {
        return new PickerStep.Stay(new ShellPicker(choices, nextSelected, nextRemember, missing));
    }

    private static Optional<Integer> indexOf(List<ShellProfile> choices, ProfileId id) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).id().equals(id)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }
}
