package tanager.ui.sidebar;

import java.awt.BorderLayout;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import tanager.domain.history.Alias;
import tanager.domain.history.HistoryError;
import tanager.ui.i18n.Msg;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.ThemeColors;

/**
 * 별칭 입력칸(#86). 확인할 때 도메인 규칙({@link Alias#of})으로 검사한다. 틀리면 칸 아래에 이유를 보이고 결과를 주지 않는다 — 대화
 * 상자는 닫히지 않는다. 빈 값은 별칭을 지운다.
 */
final class AliasForm extends JPanel {
    private final JTextField field = new JTextField(24);
    private final JLabel error = new JLabel();

    AliasForm(Optional<String> current) {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        field.setText(current.orElse(""));
        error.setForeground(ThemeColors.get(ColorToken.BAD));
        error.setVisible(false);
        JLabel hint = new JLabel(Msg.ALIAS_HINT.text());
        hint.setForeground(ThemeColors.get(ColorToken.MUTED));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        add(hint, BorderLayout.NORTH);
        add(field, BorderLayout.CENTER);
        add(error, BorderLayout.SOUTH);
    }

    /** 확인: 맞으면 결과(빈 값 = 지움), 틀리면 오류를 보이고 비어 있다. */
    Optional<Optional<String>> submit() {
        String raw = field.getText();
        if (raw.isBlank()) {
            error.setVisible(false);
            return Optional.of(Optional.empty());
        }
        return Alias.of(raw)
                .fold(
                        alias -> {
                            error.setVisible(false);
                            return Optional.of(Optional.of(alias.value()));
                        },
                        (HistoryError.InvalidAlias invalid) -> {
                            error.setText(invalid.reason());
                            error.setVisible(true);
                            return Optional.empty();
                        });
    }

    JTextField field() {
        return field;
    }

    JLabel error() {
        return error;
    }
}
