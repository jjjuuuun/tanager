package tanager.ui.pane;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Optional;
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
import tanager.application.port.in.ListProfilesUseCase;
import tanager.application.port.in.StartFailure;
import tanager.application.port.in.StartRequest;
import tanager.application.port.in.StartSessionUseCase;
import tanager.application.port.in.StartedSession;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.application.port.out.TerminalSize;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ProfileResolution;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;
import tanager.ui.font.TerminalFontSet;
import tanager.ui.terminal.TanagerTermWidget;
import tanager.ui.terminal.TerminalSettings;
import tanager.ui.theme.JediTermColors;
import tanager.ui.theme.SoftDarkLaf;

/** 창 컨트롤러의 효과 실행. Swing 은 EDT 에서, 두 executor 는 테스트가 직접 비운다. */
class PaneControllerTest {
    private final QueueExecutor ui = new QueueExecutor();
    private final QueueExecutor background = new QueueExecutor();
    private final FakeSessions sessions = new FakeSessions();
    private final FakeProfiles profiles = new FakeProfiles(List.of(PaneFixtures.CMD, PaneFixtures.UBUNTU));
    private final RecordingHost host = new RecordingHost();
    private LookAndFeel previous;
    private PaneController controller;

    @BeforeEach
    void setUp() {
        onEdt(() -> {
            previous = UIManager.getLookAndFeel();
            UIManager.setLookAndFeel(new SoftDarkLaf());
            TerminalSettings settings = new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.current());
            controller = new PaneController(new PaneId("p1"), ui, background, sessions, profiles, settings, host);
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
    @DisplayName("셸이 경로를 알리면 머리줄에 그 경로가, 툴팁에 전체 경로가 보인다(#82)")
    void headerShowsReportedCwd() {
        // given
        startAndConnect();
        WorkspacePath work = WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\work")
                .value()
                .orElseThrow();

        // when
        onEdt(() -> {
            controller.cwdReported(work);
            return null;
        });

        // then
        assertThat(onEdt(() -> controller.header().getText())).isEqualTo("cmd · C:\\work");
        assertThat(onEdt(() -> controller.header().getToolTipText())).isEqualTo("C:\\work");
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

    // ---- 셸 선택 화면(#70) ----

    private void startPicking() {
        sessions.choiceOnRemembered = Optional.of(
                new ProfileResolution.Choose(List.of(PaneFixtures.CMD, PaneFixtures.UBUNTU), Optional.empty()));
        onEdt(() -> {
            controller.start();
            return null;
        });
        drain();
        assertThat(onEdt(controller::state)).isInstanceOf(PaneState.Picking.class);
    }

    private void exitShell() {
        startAndConnect();
        sessions.streams.getFirst().exit(0);
        drain();
        assertThat(onEdt(controller::state)).isInstanceOf(PaneState.Exited.class);
    }

    private void pressOn(java.util.function.Supplier<JComponent> target, int condition, String key) {
        onEdt(() -> {
            JComponent component = target.get();
            Object name = component.getInputMap(condition).get(KeyStroke.getKeyStroke(key));
            assertThat(name).as(key + " 바인딩").isNotNull();
            component.getActionMap().get(name).actionPerformed(null);
            return null;
        });
    }

    private void pressPicker(String key) {
        pressOn(controller::picker, JComponent.WHEN_FOCUSED, key);
    }

    private void pressNotice(String key) {
        pressOn(controller::notice, JComponent.WHEN_FOCUSED, key);
    }

    private StartRequest lastRequest() {
        return sessions.requests.getLast();
    }

    @Test
    @DisplayName("기억한 셸이 없으면 셸을 띄우지 않고 선택 화면을 본문에 놓는다")
    void choiceRequiredShowsPicker() {
        // when
        startPicking();

        // then
        assertThat(onEdt(() -> SwingUtilities.isDescendingFrom(controller.picker(), controller.component())))
                .isTrue();
        assertThat(sessions.streams).isEmpty();
    }

    @Test
    @DisplayName("선택 화면에서 아래 화살표 뒤 Enter 는 둘째 셸을 기억 켜짐으로 띄운다")
    void arrowThenEnterStartsSecondShell() {
        // given
        startPicking();

        // when
        pressPicker("DOWN");
        press("ENTER");
        drain();

        // then
        assertThat(lastRequest().profile()).contains(PaneFixtures.UBUNTU.id());
        assertThat(lastRequest().rememberAsDefault()).isTrue();
        assertThat(onEdt(controller::state)).isInstanceOf(PaneState.Running.class);
    }

    @Test
    @DisplayName("선택 화면에서 숫자 2 는 바로 둘째 셸을 띄운다")
    void digitKeyStartsSecondShell() {
        // given
        startPicking();

        // when
        pressPicker("2");
        drain();

        // then
        assertThat(lastRequest().profile()).contains(PaneFixtures.UBUNTU.id());
        assertThat(lastRequest().rememberAsDefault()).isTrue();
    }

    @Test
    @DisplayName("선택 화면에서 고른 뒤 셸이 연결되기 전까지 터미널은 포커스를 받지 않는다 — 누른 키의 글자가 새 셸로 새지 않는다")
    void terminalIsNotFocusableUntilConnected() {
        // given
        startPicking();

        // when
        pressPicker("2");

        // then
        assertThat(onEdt(controller::state)).isInstanceOf(PaneState.Launching.class);
        assertThat(onEdt(() -> controller.widget().getTerminalPanel().isFocusable()))
                .isFalse();
        drain();
        assertThat(onEdt(() -> controller.widget().getTerminalPanel().isFocusable()))
                .isTrue();
    }

    @Test
    @DisplayName("선택 화면에서 키패드 2 도 바로 둘째 셸을 띄운다")
    void numpadKeyStartsSecondShell() {
        // given
        startPicking();

        // when
        pressPicker("NUMPAD2");
        drain();

        // then
        assertThat(lastRequest().profile()).contains(PaneFixtures.UBUNTU.id());
    }

    @Test
    @DisplayName("선택 화면의 첫 행을 클릭하면 그 셸을 띄운다")
    void clickingFirstRowStartsIt() {
        // given
        startPicking();

        // when
        onEdt(() -> {
            JComponent row = findByName(controller.picker(), "picker-row-0");
            MouseEvent press = new MouseEvent(row, MouseEvent.MOUSE_PRESSED, 0L, 0, 5, 5, 1, false, MouseEvent.BUTTON1);
            for (var listener : row.getMouseListeners()) {
                listener.mousePressed(press);
            }
            return null;
        });
        drain();

        // then
        assertThat(lastRequest().profile()).contains(PaneFixtures.CMD.id());
        assertThat(lastRequest().rememberAsDefault()).isTrue();
    }

    @Test
    @DisplayName("Space 로 기억을 끄고 Enter 하면 기억 없이 띄운다")
    void spaceTogglesRememberOff() {
        // given
        startPicking();

        // when
        pressPicker("SPACE");
        press("ENTER");
        drain();

        // then
        assertThat(lastRequest().profile()).contains(PaneFixtures.CMD.id());
        assertThat(lastRequest().rememberAsDefault()).isFalse();
    }

    @Test
    @DisplayName("시작 때 연 선택 화면에서 Esc 는 창을 닫고 Host 에 알린다")
    void escInStartupPickerRemovesPane() {
        // given
        startPicking();

        // when
        press("ESCAPE");
        drain();

        // then
        assertThat(host.removed.get()).isEqualTo(1);
        assertThat(onEdt(controller::state)).isEqualTo(new PaneState.Closed());
    }

    @Test
    @DisplayName("종료 띠에서 Tab 은 셸 목록을 백그라운드에서 한 번 읽고 선택 화면을 연다, Esc 는 같은 위젯의 종료 상태로 돌아온다")
    void tabOpensPickerAndEscReturnsToSameWidget() {
        // given
        exitShell();
        PaneState exited = onEdt(controller::state);
        TanagerTermWidget widget = onEdt(controller::widget);

        // when
        pressNotice("TAB");
        // then 아직 EDT 에서 부르지 않았다
        assertThat(profiles.calls.get()).isZero();
        background.runAll();
        assertThat(profiles.calls.get()).isEqualTo(1);
        drain();

        // then
        assertThat(onEdt(controller::state)).isInstanceOf(PaneState.Picking.class);

        // when
        press("ESCAPE");
        drain();

        // then
        assertThat(onEdt(controller::state)).isEqualTo(exited);
        assertThat(onEdt(controller::widget)).isSameAs(widget);
        assertThat(onEdt(() -> SwingUtilities.isDescendingFrom(widget, controller.component())))
                .isTrue();
        assertThat(host.removed.get()).isZero();
    }

    @Test
    @DisplayName("Tab 으로 연 선택 화면에서 고르면 restart 가 아니라 고른 셸의 새 시작 요청이다")
    void pickingAfterTabStartsFreshChosenShell() {
        // given
        exitShell();
        pressNotice("TAB");
        drain();
        TanagerTermWidget old = onEdt(controller::widget);

        // when
        pressPicker("2");
        drain();

        // then
        assertThat(sessions.restarts).isEmpty();
        assertThat(lastRequest().profile()).contains(PaneFixtures.UBUNTU.id());
        assertThat(lastRequest().rememberAsDefault()).isFalse();
        assertThat(onEdt(controller::widget)).isNotSameAs(old);
    }

    @Test
    @DisplayName("종료 띠는 포커스 이동이 Tab 을 먹지 않도록 설정되어 있다")
    void noticeDisablesFocusTraversalKeys() {
        // given
        exitShell();

        // then
        assertThat(onEdt(() -> controller.notice().getFocusTraversalKeysEnabled()))
                .isFalse();
    }

    @Test
    @DisplayName("실행 중에는 창 루트에 Tab · 숫자 · 화살표 · Space 바인딩이 없어 셸이 그 키를 받는다")
    void runningHasNoPickerKeysOnRoot() {
        // given
        startAndConnect();

        // then
        for (String key : List.of("TAB", "1", "NUMPAD1", "UP", "DOWN", "SPACE")) {
            assertThat(onEdt(() -> controller
                            .component()
                            .getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                            .get(KeyStroke.getKeyStroke(key))))
                    .as(key)
                    .isNull();
        }
    }

    @Test
    @DisplayName("선택 화면에서 앱을 끝내면 띄운 셸이 없어 단계가 바로 완료된다")
    void shutdownWhilePickingCompletesImmediately() {
        // given
        startPicking();

        // when
        CompletionStage<?> done = onEdt(controller::shutdown);

        // then
        assertThat(done.toCompletableFuture()).isDone();
    }

    @Test
    @DisplayName("셸이 없음 화면에서 Tab 은 상태를 바꾸지 않고 목록도 읽지 않는다")
    void tabDoesNothingWhenNoShell() {
        // given
        sessions.noShell = true;
        onEdt(() -> {
            controller.start();
            return null;
        });
        drain();
        PaneState failed = onEdt(controller::state);
        assertThat(failed).isInstanceOf(PaneState.Failed.class);

        // when
        pressNotice("TAB");
        drain();

        // then
        assertThat(onEdt(controller::state)).isEqualTo(failed);
        assertThat(profiles.calls.get()).isZero();
    }

    private static JComponent findByName(java.awt.Container root, String name) {
        for (java.awt.Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return (JComponent) child;
            }
            if (child instanceof java.awt.Container container) {
                JComponent found = findByName(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("명세로 시작하면 그 프로파일 · 경로로 띄운다 — 트리에서 연 창(#89)")
    void startWithSpec() {
        // given
        WorkspacePath a = WorkspacePath.of(new ShellEnvironment.Windows(), "C:\\a")
                .value()
                .orElseThrow();
        PaneSpec spec = new PaneSpec(new PaneId("p1"), Optional.of(PaneFixtures.CMD.id()), Optional.of(a));

        // when
        onEdt(() -> {
            controller.start(spec);
            return null;
        });
        drain();

        // then
        StartRequest request = sessions.requests.getLast();
        assertThat(request.profile()).contains(PaneFixtures.CMD.id());
        assertThat(request.dir()).contains(new WorkingDir.At(a));
        assertThat(request.rememberAsDefault()).isFalse();
        assertThat(onEdt(controller::running)).isTrue();
    }

    @Test
    @DisplayName("창 안으로 포커스가 들어오면 Host 에 한 번 알린다(클릭 포커스, #89)")
    void focusGainedNotifiesHost() {
        // given
        startAndConnect();

        // when
        onEdt(() -> {
            controller.focusChanged(true);
            controller.focusChanged(true);
            controller.focusChanged(false);
            controller.focusChanged(true);
            return null;
        });

        // then
        assertThat(host.focusGained.get()).isEqualTo(2);
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
        final List<StartRequest> requests = new CopyOnWriteArrayList<>();
        volatile Optional<ProfileResolution.Choose> choiceOnRemembered = Optional.empty();
        volatile boolean noShell;
        final AtomicInteger starts = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean failNextStart = new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public Result<StartedSession, StartFailure> start(StartRequest request) {
            starts.incrementAndGet();
            requests.add(request);
            if (noShell) {
                return Result.err(new StartFailure.NoShellAvailable());
            }
            if (request.profile().isEmpty() && choiceOnRemembered.isPresent()) {
                return Result.err(new StartFailure.ChoiceRequired(choiceOnRemembered.get(), List.of()));
            }
            if (failNextStart.getAndSet(false)) {
                throw new IllegalStateException("주입된 기동 예외");
            }
            return Result.ok(launch());
        }

        @Override
        public Result<StartedSession, StartFailure> restart(PaneId pane, TerminalSession exited, TerminalSize size) {
            restarts.add(exited);
            return Result.ok(launch());
        }

        private StartedSession launch() {
            ScriptedTerminalStream stream = new ScriptedTerminalStream(streams.size() + 1);
            streams.add(stream);
            return PaneFixtures.started(stream, List.of());
        }
    }

    private static final class FakeProfiles implements ListProfilesUseCase {
        final List<ShellProfile> shells;
        final AtomicInteger calls = new AtomicInteger();

        FakeProfiles(List<ShellProfile> shells) {
            this.shells = List.copyOf(shells);
        }

        @Override
        public List<ShellProfile> list() {
            calls.incrementAndGet();
            return shells;
        }
    }

    private static final class RecordingHost implements PaneController.Host {
        final AtomicInteger removed = new AtomicInteger();
        final AtomicInteger focusGained = new AtomicInteger();

        @Override
        public void focusGained() {
            focusGained.incrementAndGet();
        }

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
