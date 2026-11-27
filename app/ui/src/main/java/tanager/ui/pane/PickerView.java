package tanager.ui.pane;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellProfile;
import tanager.ui.i18n.Msg;

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
    static final String TITLE = Msg.PICKER_TITLE.text();
    static final String REMEMBER_LABEL = Msg.PICKER_REMEMBER.text();
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
                Msg.PICKER_KEYS.text(escLabel(picking.exit())));
    }

    private static String missingText(ProfileId id) {
        return Msg.PICKER_MISSING.text(id.value());
    }

    private static String escLabel(PickerExit exit) {
        return switch (exit) {
            case PickerExit.CloseWindow _ -> Msg.HINT_ESC_CLOSE.text();
            case PickerExit.ReturnTo _ -> Msg.HINT_ESC_BACK.text();
        };
    }
}
