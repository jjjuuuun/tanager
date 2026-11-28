package tanager.ui.pane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.StartFailure;
import tanager.application.port.in.StartRequest;
import tanager.application.port.in.StartSessionUseCase;
import tanager.application.port.in.StartedSession;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.application.port.out.TerminalSize;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.keys.LayoutAction;
import tanager.ui.motion.Motion;
import tanager.ui.terminal.TerminalSettings;
import tanager.ui.theme.JediTermColors;
import tanager.ui.theme.SoftDarkLaf;

/**
 * 창을 되풀이해 열고 닫거나 셸을 되풀이해 다시 띄워도 Swing 쪽이 쌓이지 않는다(#223, NFR-01 · ADR-0011). 닫은 컨트롤러 · 위젯은 GC 로
 * 수거되고(전역 포커스 관리자 · 터미널 설정의 리스너가 붙잡지 않는다), 스트림 구독 · JediTerm 스레드는 기준선으로 돌아온다. 화면 없이 돈다.
 */
class PaneLifecycleLeakTest {
    private static final Motion INSTANT = new Motion(Duration.ZERO, false);
    private static final String FOCUS_OWNER = "focusOwner";
    // JediTerm 위젯이 만드는 실행기 스레드의 이름 접두(JediTermExecutorServiceManager)
    private static final String JEDITERM_THREAD = "JediTerm";
    private static final Duration COLLECT = Duration.ofSeconds(30);
    private static final int PANES = 50;
    private static final int RESTARTS = 20;

    private final QueueExecutor ui = new QueueExecutor();
    private final QueueExecutor background = new QueueExecutor();
    private final FakeSessions sessions = new FakeSessions();
    private final ListProfilesUseCase profiles = () -> List.of(PaneFixtures.CMD);
    private LookAndFeel previous;
    private TerminalSettings settings;

    @BeforeEach
    void setUp() {
        onEdt(() -> {
            previous = UIManager.getLookAndFeel();
            UIManager.setLookAndFeel(new SoftDarkLaf());
            settings = new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.current());
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        onEdt(() -> {
            UIManager.setLookAndFeel(previous);
            return null;
        });
    }

    @Test
    @DisplayName("창을 만들어 셸을 띄우고 닫기를 50번 해도 컨트롤러 · 위젯이 수거되고 포커스 리스너 · 구독 · JediTerm 스레드가 기준선이다")
    void openAndClosePanesRepeatedly() {
        // given
        int focusListeners = focusListenerCount();
        int threads = jediTermThreads();
        List<WeakReference<Object>> closed = new ArrayList<>();

        // when
        for (int round = 0; round < PANES; round++) {
            PaneController pane = newPane("p" + (round + 2));
            start(pane);
            closed.add(new WeakReference<>(pane));
            closed.add(new WeakReference<>(onEdt(pane::widget)));
            onEdt(pane::shutdown);
            drain();
        }

        // then
        assertThat(focusListenerCount()).isEqualTo(focusListeners);
        assertThat(sessions.streams).hasSize(PANES).allSatisfy(stream -> {
            assertThat(stream.isClosed()).isTrue();
            assertThat(stream.subscriberCount()).isZero();
        });
        awaitCollected(closed);
        await().atMost(COLLECT).until(() -> jediTermThreads() <= threads);
    }

    @Test
    @DisplayName("셸 종료 → 재시작을 20번 해도 끝난 위젯이 수거되고 JediTerm 스레드는 위젯 하나 몫을 넘지 않는다")
    void restartExitedShellRepeatedly() {
        // given
        int threads = jediTermThreads();
        PaneController pane = newPane("p1");
        start(pane);
        int perWidget = jediTermThreads() - threads;
        List<WeakReference<Object>> exitedWidgets = new ArrayList<>();

        // when
        for (int round = 0; round < RESTARTS; round++) {
            exitedWidgets.add(new WeakReference<>(onEdt(pane::widget)));
            sessions.streams.getLast().exit(0);
            drain();
            assertThat(onEdt(pane::state)).isInstanceOf(PaneState.Exited.class);
            onEdt(() -> {
                pane.restart();
                return null;
            });
            drain();
            assertThat(onEdt(pane::state)).isInstanceOf(PaneState.Running.class);
        }

        // then
        assertThat(sessions.streams).hasSize(RESTARTS + 1);
        assertThat(sessions.streams.subList(0, RESTARTS))
                .allSatisfy(stream -> assertThat(stream.subscriberCount()).isZero());
        awaitCollected(exitedWidgets);
        await().atMost(COLLECT).until(() -> jediTermThreads() - threads <= perWidget);
        onEdt(pane::shutdown);
        drain();
    }

    @Test
    @DisplayName("셸을 띄우기 전에 닫은 창도 수거되고 포커스 리스너가 남지 않는다")
    void closeBeforeShellStarts() {
        // given
        int focusListeners = focusListenerCount();
        List<WeakReference<Object>> closed = new ArrayList<>();

        // when
        for (int round = 0; round < PANES; round++) {
            PaneController pane = newPane("p" + (round + 2));
            onEdt(() -> {
                pane.start();
                return null;
            });
            closed.add(new WeakReference<>(pane));
            onEdt(pane::shutdown);
            drain();
        }

        // then
        assertThat(sessions.streams).isEmpty();
        assertThat(focusListenerCount()).isEqualTo(focusListeners);
        awaitCollected(closed);
    }

    private PaneController newPane(String id) {
        return onEdt(() -> new PaneController(
                new PaneId(id), ui, background, sessions, profiles, settings, () -> INSTANT, new SilentHost()));
    }

    private void start(PaneController pane) {
        onEdt(() -> {
            pane.start();
            return null;
        });
        drain();
        assertThat(onEdt(pane::state)).isInstanceOf(PaneState.Running.class);
    }

    private static int focusListenerCount() {
        return onEdt(() ->
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getPropertyChangeListeners(FOCUS_OWNER).length);
    }

    private static int jediTermThreads() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith(JEDITERM_THREAD)) {
                count++;
            }
        }
        return count;
    }

    // GC 는 요청일 뿐이다 — 수거될 때까지 다시 요청한다
    private static void awaitCollected(List<WeakReference<Object>> references) {
        await().atMost(COLLECT).pollInterval(Duration.ofMillis(100)).until(() -> {
            System.gc();
            return references.stream().allMatch(reference -> reference.get() == null);
        });
    }

    private void drain() {
        for (int round = 0; round < 100; round++) {
            boolean ran = onEdt(ui::runAll) | background.runAll();
            if (!ran) {
                return;
            }
        }
        throw new AssertionError("executor 가 비지 않는다");
    }

    private static <T> T onEdt(ThrowingSupplier<T> body) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(body.get());
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        boolean runAll() {
            boolean ran = false;
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
                ran = true;
            }
            return ran;
        }
    }

    /** 늘 가짜 스트림으로 뜨는 기동. 스트림은 테스트가 끝내거나 닫힌 것을 확인하려고 들고 있다(컨트롤러는 붙잡지 않는다). */
    private static final class FakeSessions implements StartSessionUseCase {
        final List<ScriptedTerminalStream> streams = new CopyOnWriteArrayList<>();

        @Override
        public Result<StartedSession, StartFailure> start(StartRequest request) {
            return Result.ok(launch());
        }

        @Override
        public Result<StartedSession, StartFailure> restart(PaneId pane, TerminalSession exited, TerminalSize size) {
            return Result.ok(launch());
        }

        private StartedSession launch() {
            ScriptedTerminalStream stream = new ScriptedTerminalStream(streams.size() + 1);
            streams.add(stream);
            return PaneFixtures.started(stream, List.of());
        }
    }

    private static final class SilentHost implements PaneController.Host {
        @Override
        public void viewChanged(String tabTitle, String status) {}

        @Override
        public void notify(List<String> messages) {}

        @Override
        public void removed() {}

        @Override
        public void focusGained() {}

        @Override
        public void layoutRequested(LayoutAction action) {}

        @Override
        public void menuRequested(Optional<ShellProfile> shell, Component invoker, int x, int y) {}
    }
}
