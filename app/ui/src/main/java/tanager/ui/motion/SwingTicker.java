package tanager.ui.motion;

import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.util.Optional;
import javax.swing.Timer;

/** Swing {@link Timer} 로 도는 {@link Ticker}. 콜백은 EDT 에서 불린다. 간격은 화면 주사율을 따른다(SDD FR-UI-04). */
final class SwingTicker implements Ticker {
    // 주사율을 알 수 없을 때(원격 데스크톱 · headless) 흔한 화면 값으로 본다
    private static final int FALLBACK_REFRESH_HZ = 60;
    private static final int MILLIS_PER_SECOND = 1000;

    private Optional<Timer> timer = Optional.empty();
    private Runnable onTick = () -> {};

    @Override
    public void start(Runnable onTick) {
        this.onTick = onTick;
        Timer running = timer.orElseGet(() -> {
            Timer created = new Timer(intervalMillis(refreshRate()), event -> this.onTick.run());
            created.setRepeats(true);
            created.setCoalesce(true);
            timer = Optional.of(created);
            return created;
        });
        if (!running.isRunning()) {
            running.start();
        }
    }

    @Override
    public void stop() {
        timer.ifPresent(Timer::stop);
    }

    /** 주사율(Hz) → 프레임 간격(ms, 1 이상). 모르는 값(0 이하)은 60Hz 로 본다. */
    static int intervalMillis(int refreshRate) {
        int hz = refreshRate > 0 ? refreshRate : FALLBACK_REFRESH_HZ;
        return Math.max(1, MILLIS_PER_SECOND / hz);
    }

    private static int refreshRate() {
        if (GraphicsEnvironment.isHeadless()) {
            return 0;
        }
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDisplayMode()
                    .getRefreshRate();
        } catch (HeadlessException e) {
            return 0;
        }
    }
}
