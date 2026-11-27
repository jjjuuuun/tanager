package tanager.ui;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.swing.JFrame;
import tanager.application.port.in.RecordWindowStateUseCase;
import tanager.domain.layout.Rect;
import tanager.domain.workspace.Bounds;
import tanager.domain.workspace.WindowState;

/**
 * 메인 창의 자리 · 크기 · 최대화 · 사이드바를 복원하고 따라가 기록한다(FR-RES-03, #180). EDT 에서만 쓴다.
 *
 * <p>이동 · 크기 · 창 상태 이벤트와 사이드바 접기는 {@value #QUIET_MILLIS}ms 모아 한 번 기록한다 — 끄는 동안 수십 번 보내지 않게. 기록할
 * 때 프레임을 그때 읽는다: 최대화 · 최소화로 가는 도중의 자리가 보통 크기로 들어가지 않는다(Windows 는 최대화 때 이동 이벤트가 창 상태보다 먼저
 * 올 수 있다).
 */
final class WindowTracker {
    private static final System.Logger LOG = System.getLogger(WindowTracker.class.getName());
    private static final long QUIET_MILLIS = 250;

    private final JFrame frame;
    private final SidebarSlot sidebar;
    private final RecordWindowStateUseCase recorder;
    private final Coalescer coalescer;
    private WindowState current;

    private WindowTracker(JFrame frame, SidebarSlot sidebar, RecordWindowStateUseCase recorder, WindowState placed) {
        this.frame = frame;
        this.sidebar = sidebar;
        this.recorder = recorder;
        this.current = placed;
        this.coalescer =
                new Coalescer(Duration.ofMillis(QUIET_MILLIS), Coalescer.Timer.swing(), System::nanoTime, this::record);
    }

    /**
     * 창을 띄우기 전에 부른다(최소 크기를 정한 뒤). 저장된 자리가 지금 모니터들에 보이면 그 자리, 아니면 주 모니터 가운데에 {@code
     * fallback} 크기. 최대화였으면 그 자리 위에서 최대화한다 — 최대화를 풀면 그 자리로 돌아온다. 그 뒤의 변화를 따라간다.
     */
    static WindowTracker place(
            JFrame frame,
            WindowState saved,
            SidebarSlot sidebar,
            RecordWindowStateUseCase recorder,
            Dimension fallback) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(saved, "saved");
        Objects.requireNonNull(sidebar, "sidebar");
        Objects.requireNonNull(recorder, "recorder");
        Optional<Bounds> fitted = saved.bounds().flatMap(bounds -> WindowPlacement.fit(bounds, screens()));
        if (fitted.isPresent()) {
            Bounds bounds = fitted.get();
            frame.setBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        } else {
            frame.setSize(fallback);
            frame.setLocationRelativeTo(null);
        }
        WindowState normal = WindowCapture.framed(
                new WindowState(saved.bounds(), false, sidebar.state(), saved.toolkit()),
                frame.getBounds(),
                Frame.NORMAL);
        if (saved.maximized()) {
            frame.setExtendedState(frame.getExtendedState() | Frame.MAXIMIZED_BOTH);
        }
        WindowTracker tracker = new WindowTracker(
                frame,
                sidebar,
                recorder,
                new WindowState(normal.bounds(), saved.maximized(), normal.sidebar(), normal.toolkit()));
        tracker.listen();
        // 화면 밖이라 옮긴 자리 · 줄인 사이드바 폭도 기록한다(같으면 기록기가 무시한다)
        tracker.coalescer.poke();
        return tracker;
    }

    /** 사이드바를 접었거나 폈다. */
    void sidebarChanged() {
        coalescer.poke();
    }

    /** 모아 둔 기록을 바로 보낸다 — 창을 닫기 전에(프레임이 아직 있을 때) 부른다. */
    void flush() {
        coalescer.flush();
    }

    private void listen() {
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                coalescer.poke();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                coalescer.poke();
            }
        });
        frame.addWindowStateListener(e -> coalescer.poke());
    }

    private void record() {
        WindowState framed = WindowCapture.framed(current, frame.getBounds(), frame.getExtendedState());
        current = WindowCapture.sidebar(framed, sidebar.shown());
        recorder.record(current).whenComplete((done, failure) -> {
            if (failure != null) {
                LOG.log(System.Logger.Level.DEBUG, "창 상태를 기록하지 못했다 — 종료 중이면 정상", failure);
            }
        });
    }

    private static List<Rect> screens() {
        List<Rect> screens = new ArrayList<>();
        for (GraphicsDevice device :
                GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle bounds = device.getDefaultConfiguration().getBounds();
            screens.add(new Rect(bounds.x, bounds.y, bounds.width, bounds.height));
        }
        return screens;
    }
}
