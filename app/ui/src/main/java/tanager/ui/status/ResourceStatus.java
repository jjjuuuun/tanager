package tanager.ui.status;

import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import tanager.application.port.in.ResourceReport;
import tanager.application.port.in.WatchResourcesUseCase;
import tanager.application.port.out.MonitorItem;
import tanager.application.port.out.Subscription;
import tanager.domain.monitor.UsageLevel;
import tanager.ui.html.Html;
import tanager.ui.i18n.Msg;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.ThemeColors;

/**
 * 상태줄 오른쪽의 리소스 항목들(FR-MON-01 · 03 · 04, #188). 누르면 OS 도구를 열고, 우클릭 메뉴로 항목을 끄고 켠다(그 수집도 멈춘다). 폭이 모자라면
 * {@link StatusFit} 순서로 숨긴다. EDT 에서만 만진다 — 보고는 유스케이스가 UI 스레드로 보낸다.
 */
public final class ResourceStatus {
    private static final int GAP = 14;

    private final WatchResourcesUseCase resources;
    private final Locale locale;
    private final ZoneId zone;
    private final JPanel panel = new JPanel();
    private final List<ItemLabel> labels = new ArrayList<>();
    private final MouseAdapter popup = new PopupTrigger();
    private Optional<ResourceReport> last = Optional.empty();
    // 메뉴로 끄고 켠 것. 그 전에 잰 보고가 늦게 와도 되살리지 않게, 보고의 항목이 같아질 때까지 보고보다 앞선다
    private final Map<MonitorItem, Boolean> pending = new EnumMap<>(MonitorItem.class);
    private int available = Integer.MAX_VALUE;

    /**
     * @param locale 화면 언어(앱은 표시 언어)
     * @param zone 마지막 갱신 시각을 보일 시간대
     */
    public ResourceStatus(WatchResourcesUseCase resources, Locale locale, ZoneId zone) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.locale = Objects.requireNonNull(locale, "locale");
        this.zone = Objects.requireNonNull(zone, "zone");
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setOpaque(false);
        panel.addMouseListener(popup);
    }

    /** 상태줄에 놓을 부품. */
    public JComponent component() {
        return panel;
    }

    /** 보고 구독을 시작한다. 해제하면 (다른 구독이 없을 때) 수집도 멈춘다. */
    public Subscription start() {
        return resources.subscribe(this::show);
    }

    /** {@code target} 을 우클릭해도 항목 메뉴가 뜬다(항목을 모두 꺼 빈 상태줄에서도 다시 켤 수 있게). */
    public void installMenu(JComponent target) {
        target.addMouseListener(popup);
    }

    /** 한 주기의 보고를 그린다. */
    public void show(ResourceReport report) {
        last = Optional.of(report);
        pending.entrySet().removeIf(entry -> report.items().contains(entry.getKey()) == entry.getValue());
        render();
    }

    /** 리소스 항목이 쓸 수 있는 폭(px). 모자라면 WSL → 디스크 → 메모리 순으로 숨긴다. */
    public void fitWithin(int width) {
        available = Math.max(0, width);
        fit();
    }

    /** 설정을 다시 읽었다 — 설정의 항목이 상태줄에서 끄고 켠 것을 덮으므로(ResourceWatcher) 메뉴로 바꾼 것을 잊는다. */
    public void settingsChanged() {
        pending.clear();
        render();
    }

    /** 항목마다 보이기 · 숨기기를 고르는 메뉴. */
    JPopupMenu menu() {
        Set<MonitorItem> shown = effectiveItems();
        JPopupMenu menu = new JPopupMenu();
        for (MonitorItem item : MonitorItem.values()) {
            JCheckBoxMenuItem row = new JCheckBoxMenuItem(name(item), shown.contains(item));
            row.addActionListener(e -> toggle(item, row.isSelected()));
            menu.add(row);
        }
        return menu;
    }

    private void toggle(MonitorItem item, boolean visible) {
        pending.put(item, visible);
        resources.setItemVisible(item, visible);
        render();
    }

    private Set<MonitorItem> effectiveItems() {
        Set<MonitorItem> items = EnumSet.noneOf(MonitorItem.class);
        last.ifPresent(report -> items.addAll(report.items()));
        pending.forEach((item, visible) -> {
            if (visible) {
                items.add(item);
            } else {
                items.remove(item);
            }
        });
        return items;
    }

    private void render() {
        List<ItemText> items = last.map(report -> ResourceText.format(
                        new ResourceReport(report.sample(), report.levels(), effectiveItems(), report.wslUnavailable()),
                        locale,
                        zone))
                .orElse(List.of());
        while (labels.size() < items.size()) {
            ItemLabel label = new ItemLabel();
            labels.add(label);
            panel.add(label);
        }
        while (labels.size() > items.size()) {
            panel.remove(labels.removeLast());
        }
        for (int i = 0; i < items.size(); i++) {
            labels.get(i).display(items.get(i));
        }
        fit();
    }

    private void fit() {
        List<StatusFit.Width> widths = new ArrayList<>();
        for (ItemLabel label : labels) {
            widths.add(new StatusFit.Width(label.item, label.getPreferredSize().width));
        }
        Set<MonitorItem> visible = StatusFit.visible(widths, available);
        for (ItemLabel label : labels) {
            label.setVisible(visible.contains(label.item));
        }
        panel.revalidate();
        panel.repaint();
    }

    private static String name(MonitorItem item) {
        return switch (item) {
            case CPU -> Msg.STATUS_MENU_CPU.text();
            case MEMORY -> Msg.STATUS_MENU_MEMORY.text();
            case DISK -> Msg.STATUS_MENU_DISK.text();
            case WSL -> Msg.STATUS_MENU_WSL.text();
        };
    }

    // Windows 는 놓을 때, macOS 는 누를 때가 팝업 신호다
    private final class PopupTrigger extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            maybeShow(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            maybeShow(e);
        }

        private void maybeShow(MouseEvent e) {
            if (e.isPopupTrigger()) {
                menu().show(e.getComponent(), e.getX(), e.getY());
            }
        }
    }

    /** 항목 하나. 색은 토큰으로 들고 테마가 바뀌면(updateUI) 새 테마에서 다시 읽는다. */
    private final class ItemLabel extends JLabel {
        private MonitorItem item = MonitorItem.CPU;
        private ColorToken token = ColorToken.MUTED;

        ItemLabel() {
            setBorder(new EmptyBorder(0, GAP, 0, 0));
            setIconTextGap(6);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(popup);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        resources.openTool(item);
                    }
                }
            });
        }

        void display(ItemText text) {
            item = text.item();
            token = text.level() == UsageLevel.WARN ? ColorToken.WARN : ColorToken.MUTED;
            setText(text.text());
            setForeground(ThemeColors.get(token));
            setIcon(text.bar().map(ratio -> new UsageBar(ratio, text.level())).orElse(null));
            setToolTipText(Html.lines(text.tooltip()));
        }

        // 부모 생성자가 필드보다 먼저 부른다 — 그때 token 은 아직 없다
        @Override
        public void updateUI() {
            super.updateUI();
            if (token != null) {
                setForeground(ThemeColors.get(token));
            }
        }
    }
}
