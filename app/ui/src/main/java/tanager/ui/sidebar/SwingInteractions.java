package tanager.ui.sidebar;

import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** 실제 클립보드 · 확인 대화 · 별칭 입력 대화. EDT 에서 부른다. */
final class SwingInteractions implements SidebarInteractions {
    private final Component parent;

    SwingInteractions(Component parent) {
        this.parent = parent;
    }

    @Override
    public void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    @Override
    public boolean confirmForget(String label) {
        int answer = JOptionPane.showConfirmDialog(
                parent,
                "'" + label + "' 의 기록을 지울까요? 고정 · 별칭도 함께 사라진다.",
                "기록 삭제",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return answer == JOptionPane.OK_OPTION;
    }

    // 틀린 값이면 대화 상자를 닫지 않고 칸 아래에 이유를 보인다
    @Override
    public Optional<Optional<String>> editAlias(Optional<String> current) {
        AliasForm form = new AliasForm(current);
        JOptionPane pane = new JOptionPane(form, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog dialog = pane.createDialog(parent, "별칭");
        AtomicReference<Optional<Optional<String>>> result = new AtomicReference<>(Optional.empty());
        pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY, event -> {
            if (Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue())) {
                Optional<Optional<String>> submitted = form.submit();
                if (submitted.isEmpty()) {
                    pane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                    dialog.pack();
                    return;
                }
                result.set(submitted);
            }
            dialog.setVisible(false);
        });
        SwingUtilities.invokeLater(form.field()::requestFocusInWindow);
        dialog.setVisible(true);
        dialog.dispose();
        return result.get();
    }
}
