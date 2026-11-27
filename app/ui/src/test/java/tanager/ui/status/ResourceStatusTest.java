package tanager.ui.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.ResourceReport;
import tanager.application.port.in.WatchResourcesUseCase;
import tanager.application.port.out.DirectUiDispatcher;
import tanager.application.port.out.FakeResourceProbe;
import tanager.application.port.out.FakeScheduler;
import tanager.application.port.out.MonitorItem;
import tanager.application.port.out.RecordingSystemTools;
import tanager.application.port.out.Subscription;
import tanager.application.service.ResourceWatcher;
import tanager.application.state.AppState;
import tanager.application.state.FakeClock;
import tanager.application.state.StateActor;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.WorkHistory;
import tanager.domain.monitor.ResourceLevels;
import tanager.domain.monitor.UsageLevel;
import tanager.ui.theme.ColorToken;
import tanager.ui.theme.SoftDarkLaf;
import tanager.ui.theme.SoftLightLaf;
import tanager.ui.theme.ThemeColors;

/** 상태줄 리소스 항목(Swing, FR-MON-01 · 03 · 04, #188). 모두 EDT 에서 돈다. */
class ResourceStatusTest {
    private final RecordingResources resources = new RecordingResources();
    private LookAndFeel previous;
    private ResourceStatus status;

    @BeforeEach
    void setUp() {
        onEdt(() -> {
            previous = UIManager.getLookAndFeel();
            install(new SoftDarkLaf());
            status = new ResourceStatus(resources, Locale.KOREA, ZoneOffset.UTC);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        onEdt(() -> {
            install(previous);
            return null;
        });
    }

    @Test
    @DisplayName("보고를 받으면 항목마다 글자 · 툴팁을 보이고, 보통 항목은 보조 글자색이다")
    void showsItems() {
        // when
        List<String> texts = onEdt(() -> {
            status.show(ResourceTextTest.full());
            return visibleTexts();
        });

        // then
        assertThat(texts)
                .containsExactly(
                        "CPU 23%",
                        "메모리 9.1 / 31.8 GB", "C: 210.0 / 476.0 GB", "Ubuntu 3.2 / 16.0 GB", "/ 41.0 / 1007.0 GB");
        onEdt(() -> {
            JLabel cpu = label("CPU 23%");
            assertThat(cpu.getForeground()).isEqualTo(ThemeColors.get(ColorToken.MUTED));
            assertThat(cpu.getToolTipText()).startsWith("<html>").contains("CPU 23% · 코어 8개");
            return null;
        });
    }

    @Test
    @DisplayName("메모리가 WARN 이면 글자와 막대가 warn 토큰 색이고 툴팁에 사용 가능 메모리가 있다")
    void warnColor() {
        // given
        ResourceReport report = ResourceTextTest.report(
                ResourceTextTest.host(0.23, ResourceTextTest.gb(28, 32)),
                new ResourceLevels(
                        UsageLevel.NORMAL, UsageLevel.WARN, Optional.empty(), Optional.empty(), Optional.empty()),
                EnumSet.of(MonitorItem.CPU, MonitorItem.MEMORY));

        // when / then
        onEdt(() -> {
            status.show(report);
            JLabel memory = label("메모리 28.0 / 32.0 GB");
            assertThat(memory.getForeground()).isEqualTo(ThemeColors.get(ColorToken.WARN));
            assertThat(((UsageBar) memory.getIcon()).fill()).isEqualTo(ThemeColors.get(ColorToken.WARN));
            assertThat(memory.getToolTipText()).contains("사용 가능 4.0 GB");
            assertThat(label("CPU 23%").getIcon()).isNull();
            return null;
        });
    }

    @Test
    @DisplayName("보통 메모리 막대는 강조색이다")
    void normalBar() {
        onEdt(() -> {
            status.show(ResourceTextTest.full());
            UsageBar bar = (UsageBar) label("메모리 9.1 / 31.8 GB").getIcon();
            assertThat(bar.fill()).isEqualTo(ThemeColors.get(ColorToken.ACCENT));
            assertThat(bar.ratio()).isEqualTo(9.1 / 31.8, org.assertj.core.data.Offset.offset(0.001));
            return null;
        });
    }

    @Test
    @DisplayName("테마가 바뀌면 항목 · 막대 색이 새 테마 토큰이다")
    void themeChange() {
        // given
        ResourceReport report = ResourceTextTest.report(
                ResourceTextTest.host(0.23, ResourceTextTest.gb(28, 32)),
                new ResourceLevels(
                        UsageLevel.NORMAL, UsageLevel.WARN, Optional.empty(), Optional.empty(), Optional.empty()),
                EnumSet.of(MonitorItem.CPU, MonitorItem.MEMORY));
        onEdt(() -> {
            status.show(report);
            return null;
        });

        // when / then
        onEdt(() -> {
            java.awt.Color darkMuted = ThemeColors.get(ColorToken.MUTED);
            install(new SoftLightLaf());
            SwingUtilities.updateComponentTreeUI(status.component());
            assertThat(ThemeColors.get(ColorToken.MUTED)).isNotEqualTo(darkMuted);
            assertThat(label("CPU 23%").getForeground()).isEqualTo(ThemeColors.get(ColorToken.MUTED));
            JLabel memory = label("메모리 28.0 / 32.0 GB");
            assertThat(memory.getForeground()).isEqualTo(ThemeColors.get(ColorToken.WARN));
            assertThat(((UsageBar) memory.getIcon()).fill()).isEqualTo(ThemeColors.get(ColorToken.WARN));
            return null;
        });
    }

    @Test
    @DisplayName("포커스 창이 PowerShell 이면 WSL 항목이 없고, Ubuntu 로 바뀌면 두 항목이 나타난다")
    void wslFollowsFocus() {
        // given
        ResourceReport windows = ResourceTextTest.report(
                ResourceTextTest.host(0.23, ResourceTextTest.gb(9.1, 31.8)),
                ResourceTextTest.normal(),
                EnumSet.allOf(MonitorItem.class));

        // when / then
        onEdt(() -> {
            status.show(windows);
            assertThat(visibleTexts()).noneMatch(text -> text.startsWith("Ubuntu"));
            status.show(ResourceTextTest.full());
            assertThat(visibleTexts()).contains("Ubuntu 3.2 / 16.0 GB", "/ 41.0 / 1007.0 GB");
            status.show(windows);
            assertThat(visibleTexts()).containsExactly("CPU 23%", "메모리 9.1 / 31.8 GB");
            return null;
        });
    }

    @Test
    @DisplayName("우클릭 메뉴에서 디스크를 끄면 항목이 바로 사라지고 setItemVisible(DISK, false) 를 부른다")
    void menuHidesItem() {
        // given
        onEdt(() -> {
            status.show(ResourceTextTest.full());
            return null;
        });

        // when
        onEdt(() -> {
            checkbox(status.menu(), "디스크").doClick();
            return null;
        });

        // then
        assertThat(resources.visibility).containsExactly("DISK=false");
        assertThat(onEdt(this::visibleTexts))
                .doesNotContain("C: 210.0 / 476.0 GB")
                .contains("CPU 23%");
        // 끄기 전에 잰 보고가 늦게 와도 다시 나타나지 않는다
        assertThat(onEdt(() -> {
                    status.show(ResourceTextTest.full());
                    return visibleTexts();
                }))
                .doesNotContain("C: 210.0 / 476.0 GB");
        assertThat(onEdt(() -> checkbox(status.menu(), "디스크").isSelected())).isFalse();
        assertThat(onEdt(() -> checkbox(status.menu(), "CPU").isSelected())).isTrue();
    }

    @Test
    @DisplayName("끈 항목을 메뉴에서 다시 켜면 setItemVisible(true) 를 부르고 다음 보고부터 보인다")
    void menuShowsItemAgain() {
        // given: 설정에서 디스크를 끈 보고
        ResourceReport withoutDisk = new ResourceReport(
                ResourceTextTest.full().sample(),
                ResourceTextTest.full().levels(),
                EnumSet.of(MonitorItem.CPU, MonitorItem.MEMORY, MonitorItem.WSL),
                Optional.empty());
        onEdt(() -> {
            status.show(withoutDisk);
            return null;
        });

        // when
        onEdt(() -> {
            checkbox(status.menu(), "디스크").doClick();
            return null;
        });

        // then
        assertThat(resources.visibility).containsExactly("DISK=true");
        assertThat(onEdt(() -> {
                    status.show(ResourceTextTest.full());
                    return visibleTexts();
                }))
                .contains("C: 210.0 / 476.0 GB");
    }

    @Test
    @DisplayName("설정이 바뀌면 메뉴로 끄고 켠 것보다 보고의 항목을 따른다")
    void settingsWin() {
        // given
        onEdt(() -> {
            status.show(ResourceTextTest.full());
            checkbox(status.menu(), "디스크").doClick();
            return null;
        });

        // when: 설정 파일이 항목을 바꿨다(ResourceWatcher 가 상태줄에서 끈 것을 덮는다)
        List<String> texts = onEdt(() -> {
            status.settingsChanged();
            status.show(ResourceTextTest.full());
            return visibleTexts();
        });

        // then
        assertThat(texts).contains("C: 210.0 / 476.0 GB");
    }

    @Test
    @DisplayName("CPU 항목을 누르면 openTool(CPU), 디스크를 누르면 openTool(DISK) 이다. 우클릭은 도구를 열지 않는다")
    void clickOpensTool() {
        // when
        onEdt(() -> {
            status.show(ResourceTextTest.full());
            click(label("CPU 23%"), MouseEvent.BUTTON1);
            click(label("C: 210.0 / 476.0 GB"), MouseEvent.BUTTON1);
            click(label("CPU 23%"), MouseEvent.BUTTON3);
            return null;
        });

        // then
        assertThat(resources.opened).containsExactly(MonitorItem.CPU, MonitorItem.DISK);
    }

    @Test
    @DisplayName("폭이 모자라면 WSL → 디스크 → 메모리 순으로 숨기고 CPU 는 남긴다")
    void fitsWidth() {
        onEdt(() -> {
            // given
            status.show(ResourceTextTest.full());

            // when
            status.fitWithin(1);

            // then
            assertThat(visibleTexts()).containsExactly("CPU 23%");

            // when: 다시 넓어짐
            status.fitWithin(10_000);

            // then
            assertThat(visibleTexts()).hasSize(5);
            return null;
        });
    }

    @Test
    @DisplayName("실제 수집기: 항목을 누르면 SystemTools 가 열고, 메뉴로 모두 끄면 수집이 멈춘다")
    void withWatcher() {
        // given
        StateActor state = new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), Runnable::run);
        FakeResourceProbe probe = new FakeResourceProbe();
        RecordingSystemTools tools = new RecordingSystemTools();
        FakeScheduler scheduler = new FakeScheduler();
        FakeClock clock = FakeClock.atEpoch();
        ResourceWatcher watcher =
                new ResourceWatcher(state, probe, tools, scheduler, Runnable::run, clock, new DirectUiDispatcher());
        ResourceStatus live = onEdt(() -> new ResourceStatus(watcher, Locale.KOREA, ZoneOffset.UTC));
        Subscription subscription = onEdt(() -> {
            Subscription s = live.start();
            scheduler.advance(Duration.ZERO);
            return s;
        });

        // when: CPU 를 누름
        onEdt(() -> {
            click(labelIn(live, "CPU 10%"), MouseEvent.BUTTON1);
            return null;
        });

        // then
        assertThat(tools.opened()).containsExactly(MonitorItem.CPU);

        // when: 네 항목을 모두 끔
        int before = onEdt(() -> {
            JPopupMenu menu = live.menu();
            for (String name : List.of("CPU", "메모리", "디스크", "WSL VM")) {
                checkbox(menu, name).doClick();
            }
            return probe.hostCalls();
        });
        onEdt(() -> {
            for (int s = 0; s < 10; s++) {
                clock.advance(Duration.ofSeconds(1));
                scheduler.advance(Duration.ofSeconds(1));
            }
            return null;
        });

        // then
        assertThat(probe.hostCalls()).isEqualTo(before);
        assertThat(onEdt(() -> visibleTextsIn(live))).isEmpty();
        subscription.cancel();
    }

    // --- 도우미

    private List<String> visibleTexts() {
        return visibleTextsIn(status);
    }

    private static List<String> visibleTextsIn(ResourceStatus target) {
        List<String> texts = new ArrayList<>();
        for (Component child : target.component().getComponents()) {
            if (child instanceof JLabel label && label.isVisible()) {
                texts.add(label.getText());
            }
        }
        return texts;
    }

    private JLabel label(String text) {
        return labelIn(status, text);
    }

    private static JLabel labelIn(ResourceStatus target, String text) {
        for (Component child : target.component().getComponents()) {
            if (child instanceof JLabel label
                    && label.isVisible()
                    && label.getText().equals(text)) {
                return label;
            }
        }
        throw new AssertionError("항목 없음: " + text + " — " + visibleTextsIn(target));
    }

    private static JCheckBoxMenuItem checkbox(JPopupMenu menu, String text) {
        for (Component child : menu.getComponents()) {
            if (child instanceof JCheckBoxMenuItem item && item.getText().equals(text)) {
                return item;
            }
        }
        throw new AssertionError("메뉴 항목 없음: " + text);
    }

    private static void click(JLabel label, int button) {
        int modifiers = button == MouseEvent.BUTTON1 ? MouseEvent.BUTTON1_DOWN_MASK : MouseEvent.BUTTON3_DOWN_MASK;
        for (int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            label.dispatchEvent(new MouseEvent(
                    label, id, 0L, id == MouseEvent.MOUSE_CLICKED ? 0 : modifiers, 2, 2, 1, false, button));
        }
    }

    private static void install(LookAndFeel laf) {
        try {
            UIManager.setLookAndFeel(laf);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T onEdt(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(action.get());
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (failure.get() instanceof AssertionError assertion) {
            throw assertion;
        }
        if (failure.get() != null) {
            throw new IllegalStateException(failure.get());
        }
        return result.get();
    }

    /** 끄고 켠 항목과 연 도구를 기록만 하는 유스케이스. */
    private static final class RecordingResources implements WatchResourcesUseCase {
        final List<String> visibility = new ArrayList<>();
        final List<MonitorItem> opened = new ArrayList<>();

        @Override
        public Subscription subscribe(Consumer<ResourceReport> listener) {
            return () -> {};
        }

        @Override
        public void setItemVisible(MonitorItem item, boolean visible) {
            visibility.add(item + "=" + visible);
        }

        @Override
        public void openTool(MonitorItem item) {
            opened.add(item);
        }
    }
}
