package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConversationError;
import tanager.application.port.out.ConversationScan;
import tanager.application.port.out.ConversationSource;
import tanager.application.port.out.FakeHostFiles;
import tanager.application.port.out.HomeError;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.LoadedHistory;
import tanager.application.port.out.ScriptedConversationSource;
import tanager.application.port.out.StoreAccess;
import tanager.application.port.out.ToolkitConfig;
import tanager.application.state.AppState;
import tanager.application.state.FakeClock;
import tanager.application.state.HistoryLoaded;
import tanager.application.state.ManualExecutor;
import tanager.application.state.SettingsLoaded;
import tanager.application.state.StateActor;
import tanager.application.state.Transition;
import tanager.domain.history.ConversationId;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ImportOrigin;
import tanager.domain.history.PastConversation;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.LiveSessions;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellLine;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;

/**
 * 대화 가져오기의 계기 · 겹침 · 끄기 · 실패(FR-TK-09 · FR-MON-02, #215). 시간은 {@link FakeClock}, 실행은 {@link ManualExecutor} 로
 * 결정적으로.
 */
class ConversationImporterTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ImportOrigin WIN_CLAUDE = new ImportOrigin(VisitSource.CLAUDE_CODE, WIN);
    private static final ShellProfile UBUNTU_BASH = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            UBUNTU,
            new LaunchTemplate("wsl.exe", List.of()),
            IntegrationMode.OSC7);
    private static final Instant T0 = Instant.parse("2026-11-28T00:00:00Z");
    private static final WorkspacePath WIN_HOME = path(WIN, "C:\\Users\\me");
    private static final WorkspacePath UBUNTU_HOME = path(UBUNTU, "/home/me");
    private static final WorkspacePath WORK = path(WIN, "C:\\work");
    private static final WorkspacePath UBUNTU_WORK = path(UBUNTU, "/home/me/work");
    private static final PastConversation FIRST = conversation("c1", WORK, T0.minus(Duration.ofDays(1)));
    private static final PastConversation SECOND = conversation("c2", WORK, T0.minus(Duration.ofHours(1)));

    private final ManualExecutor actorExecutor = new ManualExecutor();
    private final ManualExecutor io = new ManualExecutor();
    private final FakeClock clock = new FakeClock(T0);
    private final FakeHostFiles hostFiles =
            new FakeHostFiles().withHome(WIN, WIN_HOME).withHome(UBUNTU, UBUNTU_HOME);
    private final ScriptedConversationSource source = new ScriptedConversationSource(VisitSource.CLAUDE_CODE)
            .answer(WIN, Result.ok(new ConversationScan(List.of(SECOND, FIRST), 0)))
            .answer(
                    UBUNTU,
                    Result.ok(new ConversationScan(
                            List.of(conversation("u1", UBUNTU_WORK, T0.minus(Duration.ofMinutes(3)))), 0)));
    private final List<AppEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final StateActor actor =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), actorExecutor);
    private ConversationImporter importer;
    private int sessions;

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static PastConversation conversation(String id, WorkspacePath cwd, Instant lastActive) {
        return new PastConversation(
                VisitSource.CLAUDE_CODE, new ConversationId(id), cwd, Optional.of("요약 " + id), lastActive);
    }

    private static AppConfig withToolkit(ToolkitConfig toolkit) {
        AppConfig d = AppConfig.defaults();
        return new AppConfig(
                d.defaultProfile(),
                d.profiles(),
                d.openPlacement(),
                d.appearance(),
                d.terminal(),
                d.history(),
                d.monitor(),
                d.keys(),
                toolkit);
    }

    @BeforeEach
    void subscribe() {
        actor.subscribe(events::add);
    }

    private void start() {
        importer = new ConversationImporter(actor, List.of(source), hostFiles, io, clock);
    }

    private void settle() {
        while (actorExecutor.pending() > 0 || io.pending() > 0) {
            actorExecutor.runAll();
            io.runAll();
        }
    }

    private void loadHistory() {
        actor.submit(new HistoryLoaded(
                new LoadedHistory(WorkHistory.empty(HistoryPolicy.DEFAULT), StoreAccess.WRITABLE, List.of())));
        settle();
    }

    private void settings(AppConfig config) {
        actor.submit(new SettingsLoaded(new LoadedConfig(config, List.of())));
        settle();
    }

    private void environmentsDetected() {
        actor.submit(current -> new Transition<>(
                current.withEnvironments(List.of(WIN, UBUNTU)),
                List.of(new AppEvent.EnvironmentsChanged(List.of(WIN, UBUNTU))),
                true));
        settle();
    }

    private void ubuntuSessionStarted() {
        TerminalSession running = TerminalSession.start(UBUNTU_BASH, new WorkingDir.Home())
                .value()
                .orElseThrow()
                .launched(10 + sessions)
                .value()
                .orElseThrow();
        PaneId pane = new PaneId("p" + (++sessions));
        actor.submit(current -> {
            LiveSessions next = current.sessions().launched(pane, running);
            return new Transition<>(current.withSessions(next), List.of(new AppEvent.SessionsChanged(next)), true);
        });
        settle();
    }

    // 이력 · 설정이 준비되고 셸 탐지가 끝난 상태
    private void readyWithWindowsRead() {
        start();
        loadHistory();
        settings(AppConfig.defaults());
        environmentsDetected();
    }

    private List<ShellEnvironment> scanned() {
        return source.scans().stream().map(ScriptedConversationSource.Scan::env).toList();
    }

    private long count(Class<? extends AppEvent> type) {
        synchronized (events) {
            return events.stream().filter(type::isInstance).count();
        }
    }

    @Test
    @DisplayName(
            "셸 탐지가 끝나면 Windows 를 한 번 읽어 폴더를 claude-code 출처로 이력에 넣고 HistoryChanged · ConversationsChanged 를 한 번씩 낸다")
    void readsWindowsOnceAfterDetection() {
        // given
        start();
        loadHistory();
        settings(AppConfig.defaults());
        events.clear();

        // when
        environmentsDetected();

        // then
        assertThat(source.scans()).containsExactly(new ScriptedConversationSource.Scan(WIN, Path.of("C:\\Users\\me")));
        assertThat(actor.snapshot().history().find(WORK).orElseThrow().sources())
                .contains(VisitSource.CLAUDE_CODE);
        assertThat(actor.snapshot().conversations()).isEqualTo(Map.of(WIN_CLAUDE, List.of(SECOND, FIRST)));
        assertThat(count(AppEvent.HistoryChanged.class)).isEqualTo(1);
        assertThat(count(AppEvent.ConversationsChanged.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("Ubuntu 에 세션이 없으면 읽지 않고(홈도 묻지 않는다), 첫 세션이 Running 이 되면 한 번, 두 번째 세션에는 다시 읽지 않는다")
    void wslWaitsForFirstRunningSession() {
        // given
        readyWithWindowsRead();
        assertThat(hostFiles.homeRequests()).doesNotContain(UBUNTU);

        // when
        ubuntuSessionStarted();

        // then
        assertThat(scanned()).containsExactly(WIN, UBUNTU);
        assertThat(actor.snapshot().history().find(UBUNTU_WORK)).isPresent();

        // when
        ubuntuSessionStarted();

        // then
        assertThat(scanned()).containsExactly(WIN, UBUNTU);
    }

    @Test
    @DisplayName("창 활성화: 마지막 읽기 30초 뒤에는 읽지 않고 61초 뒤에는 읽는다")
    void refreshAfterSixtySeconds() {
        // given
        readyWithWindowsRead();

        // when
        clock.advance(Duration.ofSeconds(30));
        importer.refresh();
        settle();

        // then
        assertThat(scanned()).containsExactly(WIN);

        // when
        clock.advance(Duration.ofSeconds(31));
        importer.refresh();
        settle();

        // then
        assertThat(scanned()).containsExactly(WIN, WIN);
    }

    @Test
    @DisplayName("같은 기록을 다시 읽으면 HistoryChanged · ConversationsChanged 가 나가지 않는다")
    void rereadSameRecordIsQuiet() {
        // given
        readyWithWindowsRead();
        events.clear();

        // when
        clock.advance(Duration.ofSeconds(61));
        importer.refresh();
        settle();

        // then
        assertThat(scanned()).containsExactly(WIN, WIN);
        assertThat(count(AppEvent.HistoryChanged.class)).isZero();
        assertThat(count(AppEvent.ConversationsChanged.class)).isZero();
    }

    @Test
    @DisplayName("읽는 중에 또 계기가 오면 겹쳐 읽지 않는다")
    void doesNotOverlapReads() {
        // given
        start();
        loadHistory();
        settings(AppConfig.defaults());
        actor.submit(current -> new Transition<>(
                current.withEnvironments(List.of(WIN)), List.of(new AppEvent.EnvironmentsChanged(List.of(WIN))), true));
        actorExecutor.runAll();

        // when
        clock.advance(Duration.ofSeconds(61));
        importer.refresh();

        // then
        assertThat(io.pending()).isEqualTo(1);
        settle();
        assertThat(scanned()).containsExactly(WIN);
    }

    @Test
    @DisplayName("설정을 읽기 전 · 이력을 불러오기 전에는 읽지 않고, 둘 다 준비되면 읽는다")
    void waitsForSettingsAndHistory() {
        // given
        start();
        environmentsDetected();

        // when
        settings(AppConfig.defaults());

        // then
        assertThat(source.scans()).isEmpty();

        // when
        loadHistory();

        // then
        assertThat(scanned()).containsExactly(WIN);
        assertThat(actor.snapshot().history().find(WORK)).isPresent();
    }

    @Test
    @DisplayName("importClaudeProjects=false 가 되면 대화 목록이 비고 이력의 claude-code 출처는 남으며 더는 읽지 않는다")
    void turningOffClearsAndStops() {
        // given
        readyWithWindowsRead();
        events.clear();

        // when
        settings(withToolkit(new ToolkitConfig(List.of(), false)));

        // then
        assertThat(actor.snapshot().conversations()).isEmpty();
        assertThat(events).contains(new AppEvent.ConversationsChanged(Map.of()));
        assertThat(actor.snapshot().history().find(WORK).orElseThrow().sources())
                .contains(VisitSource.CLAUDE_CODE);

        // when
        clock.advance(Duration.ofSeconds(61));
        importer.refresh();
        ubuntuSessionStarted();
        settle();

        // then
        assertThat(scanned()).containsExactly(WIN);
    }

    @Test
    @DisplayName("다시 켜면 그 설정 변경을 계기로 다시 읽는다")
    void turningBackOnReadsAgain() {
        // given
        readyWithWindowsRead();
        settings(withToolkit(new ToolkitConfig(List.of(), false)));

        // when
        settings(AppConfig.defaults());

        // then
        assertThat(scanned()).containsExactly(WIN, WIN);
        assertThat(actor.snapshot().conversations()).containsKey(WIN_CLAUDE);
    }

    @Test
    @DisplayName("providers 에 claude-code 가 없으면(codex 만) 읽지 않는다")
    void otherProvidersOnlyMeansNoRead() {
        // given
        start();
        loadHistory();
        settings(withToolkit(new ToolkitConfig(List.of("codex"), true)));

        // when
        environmentsDetected();

        // then
        assertThat(source.scans()).isEmpty();
        assertThat(hostFiles.homeRequests()).isEmpty();
    }

    @Test
    @DisplayName("providers 가 비면 등록된 공급자 모두라 읽는다")
    void emptyProvidersMeansAll() {
        // given
        start();
        loadHistory();
        settings(withToolkit(new ToolkitConfig(List.of(), true)));

        // when
        environmentsDetected();

        // then
        assertThat(scanned()).containsExactly(WIN);
    }

    @Test
    @DisplayName("형식을 모르면 ImportNotice 를 한 번 내고 그 실행 동안 다시 읽지 않는다")
    void unknownFormatDisablesForRun() {
        // given
        source.answer(WIN, Result.err(new ConversationError.UnknownFormat()));
        start();
        loadHistory();
        settings(AppConfig.defaults());

        // when
        environmentsDetected();
        clock.advance(Duration.ofSeconds(61));
        importer.refresh();
        settings(withToolkit(new ToolkitConfig(List.of(), false)));
        settings(AppConfig.defaults());
        settle();

        // then
        assertThat(scanned()).containsExactly(WIN);
        assertThat(events)
                .filteredOn(AppEvent.ImportNotice.class::isInstance)
                .containsExactly(new AppEvent.ImportNotice(new AppEvent.ImportProblem.UnknownFormat(WIN_CLAUDE)));
    }

    @Test
    @DisplayName("읽지 못하면(Unreadable) Unreadable 알림을 낸다")
    void unreadableNotifies() {
        // given
        source.answer(WIN, Result.err(new ConversationError.Unreadable()));
        start();
        loadHistory();
        settings(AppConfig.defaults());

        // when
        environmentsDetected();

        // then
        assertThat(events).contains(new AppEvent.ImportNotice(new AppEvent.ImportProblem.Unreadable(WIN_CLAUDE)));
    }

    @Test
    @DisplayName("기록 폴더가 없으면 알림 없이 조용히 빈 목록이다")
    void noHistoryIsSilent() {
        // given
        source.answer(WIN, Result.err(new ConversationError.NoHistory()));
        start();
        loadHistory();
        settings(AppConfig.defaults());
        events.clear();

        // when
        environmentsDetected();

        // then
        assertThat(scanned()).containsExactly(WIN);
        assertThat(actor.snapshot().conversations()).isEmpty();
        assertThat(events)
                .noneMatch(e -> e instanceof AppEvent.ImportNotice || e instanceof AppEvent.ConversationsChanged);
    }

    @Test
    @DisplayName("홈을 모르면 읽지 않고, 다음 창 활성화(60초 뒤)에 다시 해 본다")
    void unknownHomeRetriesLater() {
        // given
        hostFiles.withHomeError(new HomeError.TimedOut(WIN));
        start();
        loadHistory();
        settings(AppConfig.defaults());
        environmentsDetected();
        assertThat(source.scans()).isEmpty();
        hostFiles.withHome(WIN, WIN_HOME);

        // when
        clock.advance(Duration.ofSeconds(61));
        importer.refresh();
        settle();

        // then
        assertThat(scanned()).containsExactly(WIN);
        assertThat(events).noneMatch(AppEvent.ImportNotice.class::isInstance);
    }

    @Test
    @DisplayName("공급자가 예외를 던지면 읽지 못함으로 보고 알린다")
    void throwingSourceIsUnreadable() {
        // given
        importer = new ConversationImporter(actor, List.of(new ThrowingSource()), hostFiles, io, clock);
        loadHistory();
        settings(AppConfig.defaults());

        // when
        environmentsDetected();

        // then
        assertThat(events).contains(new AppEvent.ImportNotice(new AppEvent.ImportProblem.Unreadable(WIN_CLAUDE)));
    }

    private static final class ThrowingSource implements ConversationSource {
        @Override
        public VisitSource source() {
            return VisitSource.CLAUDE_CODE;
        }

        @Override
        public boolean supports(ShellEnvironment env) {
            return true;
        }

        @Override
        public Result<ConversationScan, ConversationError> scan(ShellEnvironment env, Path hostHome) {
            throw new IllegalStateException("고장 난 공급자");
        }

        @Override
        public ShellLine resume(ConversationId id) {
            return new ShellLine("resume");
        }

        @Override
        public ShellLine continueLatest() {
            return new ShellLine("continue");
        }
    }
}
