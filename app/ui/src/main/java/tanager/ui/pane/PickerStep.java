package tanager.ui.pane;

import java.util.Objects;

/** 선택 화면 입력 하나를 처리한 결과. */
sealed interface PickerStep {

    /** 아직 고르지 않았다. */
    record Stay(ShellPicker picker) implements PickerStep {
        public Stay {
            Objects.requireNonNull(picker, "picker");
        }
    }

    /** 골랐다. */
    record Picked(Attempt.Chosen attempt) implements PickerStep {
        public Picked {
            Objects.requireNonNull(attempt, "attempt");
        }
    }
}
