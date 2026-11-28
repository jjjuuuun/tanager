package tanager.bootstrap;

import java.awt.AWTEvent;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 첫 창이 뜬 때({@code windowOpened})를 시작 시간에 기록한다(#221). UI 모듈을 건드리지 않게 AWT 전역 창 이벤트로 본다. 창이 뜨고
 * {@link #DEADLINE} 이 지나도 단계가 다 모이지 않으면(셸 선택 화면 · 셸 탐지 실패) 모인 것만으로 알린다.
 */
final class StartupWindow implements AWTEventListener {
    private static final Duration DEADLINE = Duration.ofSeconds(20);

    private final StartupClock clock;
    private final AtomicReference<Window> opened = new AtomicReference<>();

    private StartupWindow(StartupClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 앞으로 처음 뜨는 프레임을 기다린다. 창을 만들기 전에 부른다. */
    static StartupWindow watch(StartupClock clock) {
        StartupWindow watch = new StartupWindow(clock);
        Toolkit.getDefaultToolkit().addAWTEventListener(watch, AWTEvent.WINDOW_EVENT_MASK);
        return watch;
    }

    // EDT 에서 온다
    @Override
    public void eventDispatched(AWTEvent event) {
        if (event.getID() == WindowEvent.WINDOW_OPENED
                && event.getSource() instanceof Frame frame
                && opened.compareAndSet(null, frame)) {
            clock.mark(StartupPhase.WINDOW);
            Toolkit.getDefaultToolkit().removeAWTEventListener(this);
            Timer deadline = new Timer((int) DEADLINE.toMillis(), tick -> clock.deadline());
            deadline.setRepeats(false);
            deadline.start();
        }
    }

    /** 첫 창에 닫기 요청을 보낸다 — 사용자가 X 를 누른 것과 같은 종료 길(셸 정리 · 저장 · 종료)을 탄다. */
    void close() {
        SwingUtilities.invokeLater(() -> Optional.ofNullable(opened.get())
                .ifPresent(window -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING))));
    }
}
