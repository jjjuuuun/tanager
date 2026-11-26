package dev.tanager.ui.pane;

/** 선택 화면 입력. */
sealed interface PickerKey {

    /** ↑(-1) · ↓(+1). */
    record Move(int delta) implements PickerKey {
        public Move {
            if (delta != -1 && delta != 1) {
                throw new IllegalArgumentException("이동은 -1 또는 +1 이다: " + delta);
            }
        }
    }

    /** 숫자 1~9 — 그 번호의 셸을 바로 연다. */
    record Digit(int number) implements PickerKey {
        public Digit {
            if (number < 1 || number > 9) {
                throw new IllegalArgumentException("숫자는 1~9 다: " + number);
            }
        }
    }

    /** 행 클릭 — 그 행의 셸을 바로 연다. */
    record Click(int index) implements PickerKey {
        public Click {
            if (index < 0) {
                throw new IllegalArgumentException("행 위치는 0 이상이다: " + index);
            }
        }
    }

    /** "다음부터 이 셸로 바로 열기" 켜고 끄기. */
    record ToggleRemember() implements PickerKey {}
}
