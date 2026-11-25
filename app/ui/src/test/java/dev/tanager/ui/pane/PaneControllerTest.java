package dev.tanager.ui.pane;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.port.in.StartFailure;
import dev.tanager.application.port.in.StartRequest;
import dev.tanager.application.port.in.StartSessionUseCase;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.application.port.out.ScriptedTerminalStream;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.domain.session.TerminalSession;
import dev.tanager.domain.shared.Result;
import dev.tanager.ui.font.TerminalFontSet;
import dev.tanager.ui.terminal.TanagerTermWidget;
import dev.tanager.ui.terminal.TerminalSettings;
import dev.tanager.ui.theme.JediTermColors;
import dev.tanager.ui.theme.SoftDarkLaf;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 창 컨트롤러의 효과 실행. Swing 은 EDT 에서, 두 executor 는 테스트가 직접 비운다. */
class PaneControllerTest {
    private final QueueExecutor ui = new QueueExecutor();
    private final QueueExecutor background = new QueueExecutor();
    private final FakeSessions sessions = new FakeSessions();
    private final RecordingHost host = new RecordingHost();
    private LookAndFeel previous;
    private PaneController controller;

    @BeforeEach
    void setUp() {
        onEdt(() -> {
            previous = UIManager.getLookAndFeel();
            UIManager.setLookAndFeel(new SoftDarkLaf());
            TerminalSettings settings = new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.current());
            controller = new PaneController(ui, background, sessions, settings, host);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        onEdt(() -> {
            controller.shutdown();
            UIManager.setLookAndFeel(previous);
            return null;
        });
        drain();
    }

    @Test
    @DisplayName("셸이 끝난 뒤 Enter 는 끝난 세션으로 restart 를 부르고 새 위젯을 쓴다")
    void restartUsesExitedSessionAndNewWidget() {
        // given
        startAndConnect();
        sessions.streams.getFirst().exit(3);
        drain();
        TerminalSession exited = ((PaneState.Exited) onEdt(controller::state)).session();
        TanagerTermWidget old = onEdt(controller::widget);

        // when
        press("ENTER");
        drain();

        // then
        assertThat(sessions.restarts).containsExactly(exited);
        assertThat(onEdt(controller::widget)).isNotSameAs(old);
        assertThat(onEdt(controller::state)).isInstanceOf(PaneState.Running.class);
    }

    @Test
    @DisplayName("셸이 끝나면 터미널은 포커스를 받지 않는다 — 터미널을 눌러도 Enter · Esc 는 창이 받는다")
    void exitedTerminalIsNotFocusable() {
        // given
        startAndConnect();

        // when
        sessions.streams.getFirst().exit(0);
        drain();

        // then
        assertThat(onEdt(() -> controller.widget().getTerminalPanel().isFocusable()))
                .isFalse();
    }

    @Test
    @DisplayName("기동 중에 앱을 끝내면 늦게 뜬 셸의 스트림을 닫고, 돌려준 단계는 그 셸이 끝난 뒤 완료된다")
    void shutdownWhileLaunchingClosesLateStream() {
        // given
        onEdt(() -> {
            controller.start();
            return null;
        });
        ui.runAllOnEdt();

        // when
        CompletionStage<?> done = onEdt(controller::shutdown);

        // then
        assertThat(done.toCompletableFuture()).isNotDone();
        drain();
        assertThat(sessions.streams.getFirst().isClosed()).isTrue();
        assertThat(done.toCompletableFuture()).isDone();
    }

    @Test
    @DisplayName("셸이 끝난 뒤 Esc 는 창을 닫고 Host 에 한 번 알린다")
    void escRemovesPane() {
        // given
        startAndConnect();
        sessions.streams.getFirst().exit(0);
        drain();

        // when
        press("ESCAPE");
        drain();

        // then
        assertThat(host.removed.get()).isEqualTo(1);
        assertThat(onEdt(controller::state)).isEqualTo(new PaneState.Closed());
    }

    @Test
    @DisplayName("기동이 예외를 던지면 다시 시도할 수 있는 실패 화면이 되고 Enter 로 다시 띄운다")
    void startExceptionBecomesRetryableFailure() {
        // given
        sessions.failNextStart.set(true);

        // when
        onEdt(() -> {
            controller.start();
            return null;
        });
        drain();

        // then
        assertThat(onEdt(controller::state)).isInstanceOf(PaneState.Errored.class);

        // when
        press("ENTER");
        drain();

        // then
        assertThat(sessions.starts.get()).isEqualTo(2);
        assertThat(onEdt(controller::state)).isInstanceOf(PaneState.Running.class);
    }

    private void startAndConnect() {
        onEdt(() -> {
            controller.start();
            return null;
        });
        drain();
        assertThat(onEdt(controller::state)).isInstanceOf(PaneState.Running.class);
    }

    /** 창(root)에 묶인 키 동작을 실행한다 — 포커스가 창 안 어디에 있든 받는 바인딩이어야 한다. */
    private void press(String key) {
        onEdt(() -> {
            JComponent root = controller.component();
            Object name = root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .get(KeyStroke.getKeyStroke(key));
            assertThat(name).as(key + " 바인딩").isNotNull();
            Action action = root.getActionMap().get(name);
            action.actionPerformed(null);
            return null;
        });
    }

    private void drain() {
        for (int round = 0; round < 100; round++) {
            boolean ran = ui.runAllOnEdt() | background.runAll();
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

        boolean runAllOnEdt() {
            return onEdt(this::runAll);
        }
    }

    private static final class FakeSessions implements StartSessionUseCase {
        final List<ScriptedTerminalStream> streams = new CopyOnWriteArrayList<>();
        final List<TerminalSession> restarts = new CopyOnWriteArrayList<>();
        final AtomicInteger starts = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean failNextStart = new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public Result<StartedSession, StartFailure> start(StartRequest request) {
            starts.incrementAndGet();
            if (failNextStart.getAndSet(false)) {
                throw new IllegalStateException("주입된 기동 예외");
            }
            return Result.ok(launch());
        }

        @Override
        public Result<StartedSession, StartFailure> restart(TerminalSession exited, TerminalSize size) {
            restarts.add(exited);
            return Result.ok(launch());
        }

        private StartedSession launch() {
            ScriptedTerminalStream stream = new ScriptedTerminalStream(streams.size() + 1);
            streams.add(stream);
            return PaneFixtures.started(stream, List.of());
        }
    }

    private static final class RecordingHost implements PaneController.Host {
        final AtomicInteger removed = new AtomicInteger();
        final List<String> notices = new CopyOnWriteArrayList<>();

        @Override
        public void viewChanged(String tabTitle, String status) {}

        @Override
        public void notify(List<String> messages) {
            notices.addAll(messages);
        }

        @Override
        public void removed() {
            removed.incrementAndGet();
        }
    }
}
