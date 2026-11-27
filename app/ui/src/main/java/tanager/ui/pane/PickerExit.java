package tanager.ui.pane;

import java.util.Objects;

/** 선택 화면에서 Esc 를 누르면 갈 곳. */
sealed interface PickerExit {

    /** 시작 때 연 선택 화면 — 창을 닫는다(마지막 창이면 앱 종료). */
    record CloseWindow() implements PickerExit {}

    /** Tab 으로 연 선택 화면 — 이전 상태로 돌아간다. {@code previous} 는 Exited · Failed · Errored 중 하나다. */
    record ReturnTo(PaneState previous) implements PickerExit {
        public ReturnTo {
            Objects.requireNonNull(previous, "previous");
            boolean returnable = switch (previous) {
                case PaneState.Exited _, PaneState.Failed _, PaneState.Errored _ -> true;
                case PaneState.Launching _, PaneState.Running _, PaneState.Picking _, PaneState.Closed _ -> false;
            };
            if (!returnable) {
                throw new IllegalArgumentException("돌아갈 수 없는 상태다: " + previous);
            }
        }
    }
}
