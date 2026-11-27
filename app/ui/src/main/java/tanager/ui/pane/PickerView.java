package tanager.ui.pane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellProfile;

/**
 * 셸 선택 화면을 글자로 옮긴 것(순수 계산).
 *
 * @param title 제목
 * @param rows 셸 행, 탐지 순서
 * @param rememberLabel 기억 체크박스 글자
 * @param remember 기억 체크 상태
 * @param missing 기억한 셸이 사라졌다는 안내
 * @param hint 누를 수 있는 키 안내
 */
record PickerView(
        String title, List<Row> rows, String rememberLabel, boolean remember, Optional<String> missing, String hint) {
    static final String TITLE = "시작할 셸을 고르세요";
    static final String REMEMBER_LABEL = "다음부터 이 셸로 바로 열기";
    private static final String KEYS = "↑↓ 고르기 · Enter 열기 · 1~9 바로 열기 · Space 기억 · ";
    private static final int DIGIT_KEYS = 9;

    PickerView {
        Objects.requireNonNull(title, "title");
        rows = List.copyOf(rows);
        Objects.requireNonNull(rememberLabel, "rememberLabel");
        Objects.requireNonNull(missing, "missing");
        Objects.requireNonNull(hint, "hint");
    }

    /**
     * 셸 한 줄.
     *
     * @param key 바로 여는 숫자 "1".."9", 10번째부터 ""
     * @param name 프로파일 이름
     * @param detail "WSL · Ubuntu · bash" / "Windows · cmd"
     * @param selected 선택된 행
     */
    record Row(String key, String name, String detail, boolean selected) {
        Row {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(detail, "detail");
        }
    }

    static PickerView of(PaneState.Picking picking) {
        ShellPicker picker = picking.picker();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < picker.choices().size(); i++) {
            ShellProfile shell = picker.choices().get(i);
            String key = i < DIGIT_KEYS ? String.valueOf(i + 1) : "";
            rows.add(new Row(key, shell.name(), PaneView.status(shell), i == picker.selected()));
        }
        return new PickerView(
                TITLE,
                rows,
                REMEMBER_LABEL,
                picker.remember(),
                picker.missing().map(PickerView::missingText),
                KEYS + escLabel(picking.exit()));
    }

    private static String missingText(ProfileId id) {
        return "기억한 셸 " + id.value() + " 를 찾을 수 없다 — 열 셸을 골라 주세요";
    }

    private static String escLabel(PickerExit exit) {
        return switch (exit) {
            case PickerExit.CloseWindow _ -> "Esc 닫기";
            case PickerExit.ReturnTo _ -> "Esc 돌아가기";
        };
    }
}
