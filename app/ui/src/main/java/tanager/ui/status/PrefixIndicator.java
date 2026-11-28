package tanager.ui.status;

import javax.swing.JLabel;
import javax.swing.border.EmptyBorder;
import tanager.ui.i18n.Msg;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.Themed;

/** 상태줄 왼쪽의 prefix 모드 표시(FR-LAY-08, #193). prefix 를 누른 뒤 다음 키를 기다리는 동안만 보인다. EDT 에서만 쓴다. */
public final class PrefixIndicator {
    private final JLabel label = new JLabel(Msg.STATUS_PREFIX.text());

    public PrefixIndicator() {
        Themed.foreground(label, ColorToken.ACCENT);
        label.setBorder(new EmptyBorder(0, 0, 0, 10));
        label.setVisible(false);
    }

    /** 상태줄에 놓을 부품. */
    public JLabel component() {
        return label;
    }

    /** prefix 모드가 켜졌거나({@code true}) 끝났다. */
    public void show(boolean on) {
        label.setVisible(on);
    }
}
