package dev.tanager.ui.pane;

import dev.tanager.ui.theme.ColorToken;
import dev.tanager.ui.theme.ThemeColors;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

/**
 * 셸 선택 화면(Swing 쪽). {@link PickerView} 를 그리고 입력을 사건으로 넘기기만 한다 — 선택 상태의 원본은 {@link ShellPicker} 다.
 * 키는 이 패널이 포커스를 가졌을 때만 받는다(실행 중 셸의 숫자 · 화살표 · Space 를 가로채지 않게). Enter · Esc 는 창 루트가 받는다.
 */
final class ShellPickerPanel extends JPanel {
    private static final int MAX_WIDTH = 560;
    private static final int ROW_HEIGHT = 32;
    private static final int ROW_PADDING = 12;
    private static final int KEY_WIDTH = 24;
    private static final int ACCENT_BAR = 2;
    private static final int GAP = 8;

    private final Consumer<PaneEvent> events;
    private final JLabel title = new JLabel();
    private final JLabel missing = new JLabel();
    private final JPanel rows = new JPanel();
    private final JScrollPane scroll = new JScrollPane(rows);
    private final JCheckBox remember = new JCheckBox();
    private final JLabel hint = new JLabel();

    ShellPickerPanel(Consumer<PaneEvent> events) {
        super(new GridBagLayout());
        this.events = Objects.requireNonNull(events, "events");
        setOpaque(false);
        setFocusable(true);
        // 선택 화면에서 Tab 은 아무 일도 하지 않는다 — 포커스가 패널 밖으로 새지 않게 한다
        setFocusTraversalKeysEnabled(false);
        add(column(), new GridBagConstraints());
        bindKeys();
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
            }
        });
    }

    /** {@code view} 대로 다시 그린다. */
    void render(PickerView view) {
        title.setText(view.title());
        missing.setText(view.missing().orElse(""));
        missing.setVisible(view.missing().isPresent());
        rows.removeAll();
        for (int i = 0; i < view.rows().size(); i++) {
            rows.add(row(i, view.rows().get(i)));
        }
        remember.setText(view.rememberLabel());
        remember.setSelected(view.remember());
        hint.setText(view.hint());
        revalidate();
        repaint();
        // 새 행이 배치된 뒤에 스크롤한다 — 지금은 bounds 가 0 이다
        SwingUtilities.invokeLater(() -> scrollToSelected(view));
    }

    private JPanel column() {
        JPanel column = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                return new Dimension(Math.min(size.width, MAX_WIDTH), size.height);
            }
        };
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);

        title.setForeground(ThemeColors.get(ColorToken.TEXT));
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2f));
        missing.setForeground(ThemeColors.get(ColorToken.WARN));
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setFocusable(false);
        scroll.getVerticalScrollBar().setFocusable(false);
        remember.setFocusable(false);
        remember.setOpaque(false);
        remember.setForeground(ThemeColors.get(ColorToken.TEXT));
        remember.addActionListener(e -> {
            // 체크 상태는 상태에서 다시 칠한다(원본은 하나)
            events.accept(new PaneEvent.PickerInput(new PickerKey.ToggleRemember()));
        });
        hint.setForeground(ThemeColors.get(ColorToken.MUTED));

        for (JComponent part : new JComponent[] {title, missing, scroll, remember, hint}) {
            part.setAlignmentX(LEFT_ALIGNMENT);
        }
        column.add(title);
        column.add(Box.createVerticalStrut(GAP));
        column.add(missing);
        column.add(Box.createVerticalStrut(GAP));
        column.add(scroll);
        column.add(Box.createVerticalStrut(GAP));
        column.add(remember);
        column.add(Box.createVerticalStrut(GAP));
        column.add(hint);
        return column;
    }

    private JComponent row(int index, PickerView.Row content) {
        RowPanel row = new RowPanel(content.selected());
        row.setName("picker-row-" + index);
        JLabel key = new JLabel(content.key());
        key.setPreferredSize(new Dimension(KEY_WIDTH, ROW_HEIGHT));
        key.setForeground(ThemeColors.get(content.selected() ? ColorToken.ACCENT : ColorToken.MUTED));
        JLabel name = new JLabel(content.name());
        name.setForeground(ThemeColors.get(ColorToken.TEXT));
        JLabel detail = new JLabel(content.detail());
        detail.setForeground(ThemeColors.get(ColorToken.MUTED));
        detail.setBorder(new EmptyBorder(0, ROW_PADDING, 0, 0));
        row.add(key, BorderLayout.WEST);
        row.add(name, BorderLayout.CENTER);
        row.add(detail, BorderLayout.EAST);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    events.accept(new PaneEvent.PickerInput(new PickerKey.Click(index)));
                }
            }
        });
        return row;
    }

    private void scrollToSelected(PickerView view) {
        for (int i = 0; i < view.rows().size(); i++) {
            if (view.rows().get(i).selected() && i < rows.getComponentCount()) {
                JComponent selected = (JComponent) rows.getComponent(i);
                selected.scrollRectToVisible(new Rectangle(0, 0, 1, ROW_HEIGHT));
            }
        }
    }

    private void bindKeys() {
        bind("UP", new PickerKey.Move(-1));
        bind("KP_UP", new PickerKey.Move(-1));
        bind("DOWN", new PickerKey.Move(1));
        bind("KP_DOWN", new PickerKey.Move(1));
        for (int n = 1; n <= 9; n++) {
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_0 + n, 0), "digit-" + n, new PickerKey.Digit(n));
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_NUMPAD0 + n, 0), "numpad-" + n, new PickerKey.Digit(n));
        }
        bind("SPACE", new PickerKey.ToggleRemember());
    }

    private void bind(String key, PickerKey input) {
        bind(KeyStroke.getKeyStroke(key), "tanager.picker." + key, input);
    }

    private void bind(KeyStroke stroke, String name, PickerKey input) {
        getInputMap(WHEN_FOCUSED).put(stroke, name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                events.accept(new PaneEvent.PickerInput(input));
            }
        });
    }

    /** 셸 한 줄. 선택된 행은 둥근 선택색 바탕에 왼쪽 강조 막대를 칠한다. 포커스는 받지 않는다(버튼이 아니다). */
    private static final class RowPanel extends JPanel {
        private final int arc = UIManager.getInt("Tanager.pane.arc");
        private final boolean selected;

        RowPanel(boolean selected) {
            super(new BorderLayout());
            this.selected = selected;
            setOpaque(false);
            setFocusable(false);
            setBorder(new EmptyBorder(0, ROW_PADDING, 0, ROW_PADDING));
            setAlignmentX(LEFT_ALIGNMENT);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, ROW_HEIGHT);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, ROW_HEIGHT);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (!selected) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ThemeColors.get(ColorToken.SELECT));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc));
                g2.setColor(ThemeColors.get(ColorToken.ACCENT));
                g2.fillRect(0, GAP / 2, ACCENT_BAR, getHeight() - GAP);
            } finally {
                g2.dispose();
            }
        }
    }
}
