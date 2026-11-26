package dev.tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.port.in.RecordingVisits;
import dev.tanager.application.port.in.StartFailure;
import dev.tanager.application.port.in.StartNotice;
import dev.tanager.application.port.in.StartRequest;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.application.port.out.AppConfig;
import dev.tanager.application.port.out.ConfigError;
import dev.tanager.application.port.out.ConfigWarning;
import dev.tanager.application.port.out.FakePlatformModule;
import dev.tanager.application.port.out.FakePlatformModule.LaunchRequest;
import dev.tanager.application.port.out.InMemoryConfigRepository;
import dev.tanager.application.port.out.LaunchError;
import dev.tanager.application.port.out.LoadedConfig;
import dev.tanager.application.port.out.OutputCollector;
import dev.tanager.application.port.out.ProfileSettings;
import dev.tanager.application.port.out.ScriptedPtyLauncher;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.application.state.AppState;
import dev.tanager.application.state.StateActor;
import dev.tanager.domain.history.HistoryPolicy;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ProfileResolution;
import dev.tanager.domain.session.SessionError;
import dev.tanager.domain.session.SessionState;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.TerminalSession;
import dev.tanager.domain.session.WorkingDir;
import dev.tanager.domain.shared.PaneId;
import dev.tanager.domain.shared.Result;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SessionStarterTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellProfile WSL_A = profile("wsl:A", ShellKind.BASH, new ShellEnvironment.Wsl("A"));
    private static final ShellProfile WSL_B = profile("wsl:B", ShellKind.BASH, new ShellEnvironment.Wsl("B"));
    private static final ShellProfile POWERSHELL = profile("powershell", ShellKind.WINDOWS_POWERSHELL, WIN);
    private static final ShellProfile CMD = profile("cmd", ShellKind.CMD, WIN);
    private static final TerminalSize SIZE = new TerminalSize(100, 30);
    private static final PaneId PANE = new PaneId("p1");

    private final FakePlatformModule platform = new FakePlatformModule(List.of(WSL_A, WSL_B, POWERSHELL, CMD));
    private final InMemoryConfigRepository config = new InMemoryConfigRepository();
    private final ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();
    private final SessionStarter starter = newStarter(platform);

    private SessionStarter newStarter(FakePlatformModule fakePlatform) {
        // 상태 추적은 이 테스트의 관심사가 아니다 — 방문 기록은 버리는 Fake 로 받는다(추적은 SessionStarterTrackingTest)
        StateActor state = new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), Runnable::run);
        return new SessionStarter(
                new ProfileCatalog(fakePlatform),
                config,
                fakePlatform,
                new IntegrationStatus(fakePlatform),
                launcher,
                new DefaultProfileMemory(config),
                state,
                new RecordingVisits());
    }

    private static ShellProfile profile(String id, ShellKind kind, ShellEnvironment env) {
        return new ShellProfile(
                new ProfileId(id), id, kind, env, new LaunchTemplate(id + ".exe", List.of()), IntegrationMode.NONE);
    }

    private static StartRequest request() {
        return StartRequest.remembered(PANE, SIZE);
    }

    private static StartRequest requestFor(String profileId) {
        return StartRequest.chosen(PANE, new ProfileId(profileId), false, SIZE);
    }

    private static StartRequest chosen(String profileId, boolean remember) {
        return StartRequest.chosen(PANE, new ProfileId(profileId), remember, SIZE);
    }

    private static List<ShellProfile> detected() {
        return List.of(WSL_A, WSL_B, POWERSHELL, CMD);
    }

    private static WorkspacePath winPath(String raw) {
        return WorkspacePath.of(WIN, raw).value().orElseThrow();
    }

    private static WorkspacePath wslPath(String distro, String raw) {
        return WorkspacePath.of(new ShellEnvironment.Wsl(distro), raw).value().orElseThrow();
    }

    private static Result<LoadedConfig, ConfigError> configOf(Optional<ProfileId> defaultProfile, String... idAndDir) {
        Map<ProfileId, ProfileSettings> profiles = new HashMap<>();
        for (int i = 0; i < idAndDir.length; i += 2) {
            profiles.put(new ProfileId(idAndDir[i]), new ProfileSettings(Optional.of(idAndDir[i + 1])));
        }
        return Result.ok(new LoadedConfig(new AppConfig(defaultProfile, profiles), List.of()));
    }

    private StartedSession started(Result<StartedSession, StartFailure> result) {
        assertThat(result.isOk()).as("시작 결과 %s", result).isTrue();
        return result.value().orElseThrow();
    }

    // ---- 인수 시나리오 ----

    @Test
    @DisplayName("설정이 없어도 고른 셸은 홈에서 띄우고 알림이 없다")
    void startsChosenShellAtHomeWithoutConfig() {
        // when
        StartedSession session = started(starter.start(requestFor("wsl:A")));

        // then
        assertThat(session.session().profile()).isEqualTo(WSL_A);
        assertThat(session.session().state())
                .isEqualTo(new SessionState.Running(session.stream().pid(), new WorkingDir.Home()));
        assertThat(platform.launchRequests()).containsExactly(new LaunchRequest(WSL_A, new WorkingDir.Home()));
        assertThat(launcher.commands()).hasSize(1);
        assertThat(launcher.sizes()).containsExactly(SIZE);
        assertThat(session.notices()).isEmpty();
    }

    @Test
    @DisplayName("시작 경로가 없어서 기동에 실패하면 홈에서 한 번 더 띄우고 알린다")
    void retriesAtHomeWhenStartDirIsMissing() {
        // given
        config.willLoad(configOf(Optional.empty(), "powershell", "C:\\없음"));
        launcher.willFailFor(
                command -> command.workingDirectory().equals(Optional.of("C:\\없음")),
                new LaunchError.WorkingDirectoryNotFound("C:\\없음"));

        // when
        StartedSession session = started(starter.start(requestFor("powershell")));

        // then
        assertThat(platform.launchRequests())
                .containsExactly(
                        new LaunchRequest(POWERSHELL, new WorkingDir.At(winPath("C:\\없음"))),
                        new LaunchRequest(POWERSHELL, new WorkingDir.Home()));
        assertThat(session.notices()).containsExactly(new StartNotice.StartDirMissing(winPath("C:\\없음")));
        assertThat(session.session().state())
                .isEqualTo(new SessionState.Running(session.stream().pid(), new WorkingDir.Home()));
    }

    @Test
    @DisplayName("설정 파일을 읽지 못하면 기본값으로 띄우고 알리며 저장하지 않는다")
    void startsWithDefaultsWhenConfigIsUnreadable() {
        // given
        ConfigError.Corrupt error = new ConfigError.Corrupt("줄 3");
        config.willFailLoad(error);

        // when
        StartedSession session = started(starter.start(requestFor("wsl:A")));

        // then
        assertThat(session.session().profile()).isEqualTo(WSL_A);
        assertThat(session.notices()).containsExactly(new StartNotice.ConfigUnreadable(error));
        assertThat(config.saves()).isEmpty();
    }

    @Test
    @DisplayName("설정을 읽다 난 경고는 알림으로 전달한다")
    void forwardsConfigWarnings() {
        // given
        ConfigWarning warning = new ConfigWarning.IgnoredValue("defaultProfile", "문자열이 아니다");
        config.willLoad(Result.ok(new LoadedConfig(AppConfig.defaults(), List.of(warning))));

        // when
        StartedSession session = started(starter.start(requestFor("wsl:A")));

        // then
        assertThat(session.notices()).containsExactly(new StartNotice.ConfigWarned(warning));
    }

    // ---- 시작 경로 결정 ----

    @Test
    @DisplayName("프로파일의 시작 경로 설정이 있으면 그 경로에서 띄운다")
    void usesConfiguredStartDir() {
        // given
        config.willLoad(configOf(Optional.empty(), "wsl:A", "/home/me/proj"));

        // when
        StartedSession session = started(starter.start(requestFor("wsl:A")));

        // then
        assertThat(platform.launchRequests())
                .containsExactly(new LaunchRequest(WSL_A, new WorkingDir.At(wslPath("A", "/home/me/proj"))));
        assertThat(session.notices()).isEmpty();
    }

    @Test
    @DisplayName("상대 경로 시작 설정은 홈으로 대체하고 원문과 함께 알린다")
    void relativeStartDirFallsBackToHome() {
        // given
        config.willLoad(configOf(Optional.empty(), "wsl:A", "foo"));

        // when
        StartedSession session = started(starter.start(requestFor("wsl:A")));

        // then
        assertThat(platform.launchRequests()).containsExactly(new LaunchRequest(WSL_A, new WorkingDir.Home()));
        assertThat(session.notices()).containsExactly(new StartNotice.StartDirInvalid("foo"));
    }

    @Test
    @DisplayName("시작 경로 설정 ~ 는 홈이고 알림이 없다")
    void tildeStartDirMeansHome() {
        // given
        config.willLoad(configOf(Optional.empty(), "wsl:A", "~"));

        // when
        StartedSession session = started(starter.start(requestFor("wsl:A")));

        // then
        assertThat(platform.launchRequests()).containsExactly(new LaunchRequest(WSL_A, new WorkingDir.Home()));
        assertThat(session.notices()).isEmpty();
    }

    @Test
    @DisplayName("요청이 경로를 지정하면 설정의 시작 경로보다 우선한다")
    void requestedDirBeatsConfig() {
        // given
        config.willLoad(configOf(Optional.empty(), "powershell", "C:\\설정"));
        WorkingDir dir = new WorkingDir.At(winPath("C:\\요청"));

        // when
        started(starter.start(
                new StartRequest(PANE, Optional.of(new ProfileId("powershell")), false, Optional.of(dir), SIZE)));

        // then
        assertThat(platform.launchRequests()).containsExactly(new LaunchRequest(POWERSHELL, dir));
    }

    @Test
    @DisplayName("프로파일과 환경이 다른 경로를 요청하면 거부한다")
    void rejectsDirOfOtherEnvironment() {
        // given
        WorkingDir dir = new WorkingDir.At(winPath("C:\\work"));

        // when
        var result = starter.start(
                new StartRequest(PANE, Optional.of(new ProfileId("wsl:A")), false, Optional.of(dir), SIZE));

        // then
        assertThat(result.error())
                .contains(new StartFailure.Rejected(
                        new SessionError.EnvironmentMismatch(new ShellEnvironment.Wsl("A"), WIN)));
        assertThat(launcher.commands()).isEmpty();
    }

    // ---- 실패 ----

    @Test
    @DisplayName("그 밖의 기동 오류는 Failed 세션과 오류를 담은 LaunchFailed 다")
    void otherLaunchErrorsFail() {
        // given
        LaunchError error = new LaunchError.StartFailed("conpty 없음");
        launcher.willFail(error);

        // when
        var result = starter.start(requestFor("wsl:A"));

        // then
        StartFailure failure = result.error().orElseThrow();
        assertThat(failure).isInstanceOf(StartFailure.LaunchFailed.class);
        StartFailure.LaunchFailed failed = (StartFailure.LaunchFailed) failure;
        assertThat(failed.error()).isEqualTo(error);
        assertThat(failed.failed().state()).isEqualTo(new SessionState.Failed(error.describe()));
        assertThat(failed.notices()).isEmpty();
        assertThat(platform.launchRequests()).hasSize(1);
    }

    @Test
    @DisplayName("셸이 하나도 없으면 NoShellAvailable 이고 기동하지 않는다")
    void noShellAvailable() {
        // given
        FakePlatformModule empty = new FakePlatformModule(List.of());

        // when
        var result = newStarter(empty).start(request());

        // then
        assertThat(result.error()).contains(new StartFailure.NoShellAvailable());
        assertThat(launcher.commands()).isEmpty();
    }

    // ---- 홈 재시도의 경계 ----

    @Test
    @DisplayName("홈에서 띄우다 작업 디렉터리 오류가 나면 다시 시도하지 않고 LaunchFailed 다")
    void homeLaunchMissingDirectoryIsNotRetried() {
        // given
        LaunchError error = new LaunchError.WorkingDirectoryNotFound("C:\\Users\\me");
        launcher.willFail(error);

        // when
        var result = starter.start(requestFor("wsl:A"));

        // then
        StartFailure.LaunchFailed failed =
                (StartFailure.LaunchFailed) result.error().orElseThrow();
        assertThat(failed.error()).isEqualTo(error);
        assertThat(failed.notices()).isEmpty();
        assertThat(launcher.commands()).hasSize(1);
    }

    @Test
    @DisplayName("시작 경로도 홈도 실패하면 LaunchFailed 이고 시작 경로 알림은 남는다")
    void missingStartDirThenHomeFailureKeepsNotice() {
        // given
        config.willLoad(configOf(Optional.empty(), "powershell", "C:\\없음"));
        LaunchError error = new LaunchError.WorkingDirectoryNotFound("어디든");
        launcher.willFail(error);

        // when
        var result = starter.start(requestFor("powershell"));

        // then
        StartFailure.LaunchFailed failed =
                (StartFailure.LaunchFailed) result.error().orElseThrow();
        assertThat(failed.error()).isEqualTo(error);
        assertThat(failed.failed().state()).isEqualTo(new SessionState.Failed(error.describe()));
        assertThat(failed.notices()).containsExactly(new StartNotice.StartDirMissing(winPath("C:\\없음")));
        assertThat(platform.launchRequests())
                .containsExactly(
                        new LaunchRequest(POWERSHELL, new WorkingDir.At(winPath("C:\\없음"))),
                        new LaunchRequest(POWERSHELL, new WorkingDir.Home()));
        assertThat(launcher.commands()).hasSize(2);
    }

    @Test
    @DisplayName("restart 할 때 마지막 경로가 사라졌으면 홈에서 다시 띄우고 알린다")
    void restartFallsBackToHomeWhenLastCwdIsMissing() {
        // given
        WorkingDir work = new WorkingDir.At(winPath("C:\\work"));
        StartedSession first = started(starter.start(
                new StartRequest(PANE, Optional.of(new ProfileId("powershell")), false, Optional.of(work), SIZE)));
        TerminalSession exited = first.session().exited(0).value().orElseThrow();
        launcher.willFailFor(
                command -> command.workingDirectory().equals(Optional.of("C:\\work")),
                new LaunchError.WorkingDirectoryNotFound("C:\\work"));

        // when
        StartedSession second = started(starter.restart(PANE, exited, SIZE));

        // then
        assertThat(second.notices()).containsExactly(new StartNotice.StartDirMissing(winPath("C:\\work")));
        assertThat(second.session().state())
                .isEqualTo(new SessionState.Running(second.stream().pid(), new WorkingDir.Home()));
        assertThat(platform.launchRequests())
                .containsExactly(
                        new LaunchRequest(POWERSHELL, work),
                        new LaunchRequest(POWERSHELL, work),
                        new LaunchRequest(POWERSHELL, new WorkingDir.Home()));
    }

    @Test
    @DisplayName("알림은 설정 경고 → 시작 경로 순서다")
    void noticesFollowDecisionOrder() {
        // given
        ConfigWarning warning = new ConfigWarning.IgnoredValue("profiles.x", "객체가 아니다");
        config.willLoad(Result.ok(new LoadedConfig(
                new AppConfig(
                        Optional.of(new ProfileId("wsl:Gone")),
                        Map.of(new ProfileId("wsl:A"), new ProfileSettings(Optional.of("/gone")))),
                List.of(warning))));
        launcher.willFailFor(
                command -> command.workingDirectory().equals(Optional.of("/gone")),
                new LaunchError.WorkingDirectoryNotFound("/gone"));

        // when
        StartedSession session = started(starter.start(requestFor("wsl:A")));

        // then
        assertThat(session.notices())
                .containsExactly(
                        new StartNotice.ConfigWarned(warning), new StartNotice.StartDirMissing(wslPath("A", "/gone")));
    }

    @Test
    @DisplayName("잘못된 시작 경로 알림도 설정 경고 뒤에 온다")
    void invalidStartDirNoticeComesLast() {
        // given
        ConfigWarning warning = new ConfigWarning.IgnoredValue("profiles.x", "객체가 아니다");
        config.willLoad(Result.ok(new LoadedConfig(
                new AppConfig(
                        Optional.of(new ProfileId("wsl:Gone")),
                        Map.of(new ProfileId("wsl:A"), new ProfileSettings(Optional.of("foo")))),
                List.of(warning))));

        // when
        StartedSession session = started(starter.start(requestFor("wsl:A")));

        // then
        assertThat(session.notices())
                .containsExactly(new StartNotice.ConfigWarned(warning), new StartNotice.StartDirInvalid("foo"));
    }

    // ---- 종료와 재시작 ----

    @Test
    @DisplayName("반환한 스트림은 아직 읽기를 시작하지 않았다")
    void returnedStreamIsNotStarted() {
        // given
        StartedSession session = started(starter.start(requestFor("wsl:A")));
        OutputCollector out = new OutputCollector();
        session.stream().subscribe(out);
        launcher.streams().getFirst().emit("prompt");

        // then
        assertThat(out.text()).isEmpty();

        // when
        session.stream().start();

        // then
        assertThat(out.text()).isEqualTo("prompt");
    }

    @Test
    @DisplayName("셸이 끝나면 exited 가 같은 경로를 가진 Exited 세션으로 완료된다")
    void exitedCompletesWithExitedSession() {
        // given
        StartedSession session = started(starter.start(requestFor("wsl:A")));
        launcher.streams().getFirst().exit(2);

        // when
        session.stream().start();

        // then
        assertThat(session.exited().toCompletableFuture())
                .isCompleted()
                .isCompletedWithValue(new TerminalSession(WSL_A, new SessionState.Exited(2, new WorkingDir.Home())));
    }

    @Test
    @DisplayName("restart 는 같은 프로파일과 경로로 다시 띄우고 설정을 다시 읽지 않는다")
    void restartReusesProfileAndDirWithoutReloadingConfig() {
        // given
        StartedSession first = started(starter.start(requestFor("wsl:A")));
        launcher.streams().getFirst().exit(2);
        first.stream().start();
        TerminalSession exited = first.exited().toCompletableFuture().join();
        int loadsBefore = config.loadCalls();

        // when
        StartedSession second = started(starter.restart(PANE, exited, new TerminalSize(120, 40)));

        // then
        assertThat(second.session().profile()).isEqualTo(WSL_A);
        assertThat(second.session().state()).isInstanceOf(SessionState.Running.class);
        assertThat(platform.launchRequests())
                .containsExactly(
                        new LaunchRequest(WSL_A, new WorkingDir.Home()),
                        new LaunchRequest(WSL_A, new WorkingDir.Home()));
        assertThat(launcher.sizes()).containsExactly(SIZE, new TerminalSize(120, 40));
        assertThat(config.loadCalls()).isEqualTo(loadsBefore);
        assertThat(config.saves()).isEmpty();
    }

    @Test
    @DisplayName("restart 는 마지막으로 알려진 cwd 에서 다시 띄운다")
    void restartStartsAtLastCwd() {
        // given
        StartedSession first = started(starter.start(requestFor("wsl:A")));
        WorkspacePath last = wslPath("A", "/srv/app");
        TerminalSession exited = first.session()
                .cwdChanged(last)
                .value()
                .orElseThrow()
                .exited(0)
                .value()
                .orElseThrow();

        // when
        started(starter.restart(PANE, exited, SIZE));

        // then
        assertThat(platform.launchRequests().getLast()).isEqualTo(new LaunchRequest(WSL_A, new WorkingDir.At(last)));
    }

    @Test
    @DisplayName("끝나지 않은 세션의 restart 는 거부한다")
    void restartRejectsRunningSession() {
        // given
        StartedSession first = started(starter.start(requestFor("wsl:A")));

        // when
        var result = starter.restart(PANE, first.session(), SIZE);

        // then
        assertThat(result.error())
                .contains(new StartFailure.Rejected(
                        new SessionError.IllegalTransition(first.session().state(), "restart")));
        assertThat(launcher.commands()).hasSize(1);
    }

    // ---- 셸 선택(FR-SES-02, #70) ----

    private StartFailure.ChoiceRequired choiceRequired(Result<StartedSession, StartFailure> result) {
        assertThat(result.isOk()).as("시작 결과 %s", result).isFalse();
        StartFailure failure = result.error().orElseThrow();
        assertThat(failure).isInstanceOf(StartFailure.ChoiceRequired.class);
        return (StartFailure.ChoiceRequired) failure;
    }

    private void assertNothingLaunchedOrSaved() {
        assertThat(launcher.commands()).isEmpty();
        assertThat(platform.launchRequests()).isEmpty();
        assertThat(config.saves()).isEmpty();
    }

    @Test
    @DisplayName("기억한 셸이 없으면 아무것도 띄우지 않고 탐지 순서 그대로 선택을 요구한다")
    void requiresChoiceWithoutRememberedShell() {
        // when
        var result = starter.start(request());

        // then
        StartFailure.ChoiceRequired failure = choiceRequired(result);
        assertThat(failure.choice()).isEqualTo(new ProfileResolution.Choose(detected(), Optional.empty()));
        assertThat(failure.notices()).isEmpty();
        assertNothingLaunchedOrSaved();
    }

    @Test
    @DisplayName("기억한 셸이 사라졌으면 사라진 id 와 함께 선택을 요구하고 대체하지 않는다")
    void requiresChoiceWhenRememberedShellIsGone() {
        // given
        config.willLoad(configOf(Optional.of(new ProfileId("wsl:Gone"))));

        // when
        var result = starter.start(request());

        // then
        assertThat(choiceRequired(result).choice())
                .isEqualTo(new ProfileResolution.Choose(detected(), Optional.of(new ProfileId("wsl:Gone"))));
        assertNothingLaunchedOrSaved();
    }

    @Test
    @DisplayName("기억한 셸이 목록에 있으면 선택 없이 홈에서 띄운다")
    void launchesRememberedShell() {
        // given
        config.willLoad(configOf(Optional.of(new ProfileId("powershell"))));

        // when
        StartedSession session = started(starter.start(request()));

        // then
        assertThat(session.session().profile()).isEqualTo(POWERSHELL);
        assertThat(platform.launchRequests()).containsExactly(new LaunchRequest(POWERSHELL, new WorkingDir.Home()));
        assertThat(session.notices()).isEmpty();
        assertThat(config.saves()).isEmpty();
    }

    @Test
    @DisplayName("고른 셸은 그 셸의 설정 시작 경로에서 띄운다")
    void chosenShellUsesItsStartDir() {
        // given
        config.willLoad(configOf(Optional.empty(), "cmd", "C:\\work"));

        // when
        started(starter.start(chosen("cmd", false)));

        // then
        assertThat(platform.launchRequests())
                .containsExactly(new LaunchRequest(CMD, new WorkingDir.At(winPath("C:\\work"))));
    }

    @Test
    @DisplayName("기억을 켜고 고르면 기본 셸로 저장하고 다음에는 선택 없이 그 셸이 뜬다")
    void rememberingSavesDefaultAndNextStartSkipsChoice() {
        // given
        AppConfig original = new AppConfig(
                Optional.empty(), Map.of(new ProfileId("powershell"), new ProfileSettings(Optional.of("C:\\work"))));
        config.willLoad(Result.ok(new LoadedConfig(original, List.of())));

        // when
        StartedSession first = started(starter.start(chosen("wsl:B", true)));

        // then
        assertThat(first.session().profile()).isEqualTo(WSL_B);
        assertThat(config.saves())
                .containsExactly(new AppConfig(Optional.of(new ProfileId("wsl:B")), original.profiles()));

        // when: 저장한 값을 읽도록 한다
        config.willLoad(Result.ok(new LoadedConfig(config.saves().getFirst(), List.of())));
        StartedSession second = started(starter.start(request()));

        // then
        assertThat(second.session().profile()).isEqualTo(WSL_B);
        assertThat(config.saves()).hasSize(1);
    }

    @Test
    @DisplayName("기억을 끄고 고르면 기본 셸이 있어도 그 셸만 띄우고 저장하지 않는다")
    void notRememberingDoesNotSave() {
        // given
        config.willLoad(configOf(Optional.of(new ProfileId("powershell"))));

        // when
        StartedSession session = started(starter.start(chosen("cmd", false)));

        // then
        assertThat(session.session().profile()).isEqualTo(CMD);
        assertThat(config.saves()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("saveErrors")
    @DisplayName("기본 셸을 저장하지 못해도 셸은 뜨고 마지막 알림으로 알린다")
    void saveFailureStillLaunchesAndNotifiesLast(ConfigError error) {
        // given
        config.willFailSave(error);

        // when
        StartedSession session = started(starter.start(chosen("cmd", true)));

        // then
        assertThat(session.session().profile()).isEqualTo(CMD);
        assertThat(session.notices()).last().isEqualTo(new StartNotice.DefaultNotSaved(new ProfileId("cmd"), error));
    }

    static Stream<ConfigError> saveErrors() {
        return Stream.of(new ConfigError.WouldOverwrite("새 버전"), new ConfigError.WriteFailed("disk"));
    }

    @Test
    @DisplayName("설정을 읽지 못하면 셸은 뜨지만 기본 셸을 저장하지 않고 두 알림을 준다")
    void unreadableConfigDoesNotSaveDefault() {
        // given
        ConfigError.Corrupt error = new ConfigError.Corrupt("x");
        config.willFailLoad(error);

        // when
        StartedSession session = started(starter.start(chosen("cmd", true)));

        // then
        assertThat(session.notices())
                .containsExactly(
                        new StartNotice.ConfigUnreadable(error),
                        new StartNotice.DefaultNotSaved(new ProfileId("cmd"), error));
        assertThat(config.saves()).isEmpty();
    }

    @Test
    @DisplayName("기동에 실패한 셸은 기억하지 않는다")
    void launchFailureDoesNotRemember() {
        // given
        launcher.willFail(new LaunchError.StartFailed("x"));

        // when
        var result = starter.start(chosen("cmd", true));

        // then
        assertThat(result.error().orElseThrow()).isInstanceOf(StartFailure.LaunchFailed.class);
        assertThat(config.saves()).isEmpty();
    }

    @Test
    @DisplayName("탐지 목록에 없는 셸을 요청하면 대체하지 않고 선택을 요구한다")
    void unknownRequestedShellRequiresChoice() {
        // when
        var result = starter.start(chosen("wsl:Gone", true));

        // then
        assertThat(choiceRequired(result).choice())
                .isEqualTo(new ProfileResolution.Choose(detected(), Optional.of(new ProfileId("wsl:Gone"))));
        assertNothingLaunchedOrSaved();
    }

    @Test
    @DisplayName("설정 경고는 선택 요구에도 담긴다")
    void choiceRequiredCarriesConfigWarnings() {
        // given
        ConfigWarning warning = new ConfigWarning.IgnoredValue("defaultProfile", "문자열이 아니다");
        config.willLoad(Result.ok(new LoadedConfig(AppConfig.defaults(), List.of(warning))));

        // when
        var result = starter.start(request());

        // then
        assertThat(choiceRequired(result).notices()).containsExactly(new StartNotice.ConfigWarned(warning));
    }

    @Test
    @DisplayName("설정을 읽지 못하고 요청도 없으면 선택을 요구하며 읽기 실패를 알린다")
    void unreadableConfigWithoutRequestRequiresChoice() {
        // given
        ConfigError.Corrupt error = new ConfigError.Corrupt("줄 3");
        config.willFailLoad(error);

        // when
        var result = starter.start(request());

        // then
        assertThat(choiceRequired(result).notices()).containsExactly(new StartNotice.ConfigUnreadable(error));
        assertNothingLaunchedOrSaved();
    }

    @Test
    @DisplayName("시작 경로 알림은 기본 셸 저장 실패 알림보다 앞이다")
    void startDirNoticeComesBeforeDefaultNotSaved() {
        // given
        ConfigError error = new ConfigError.WriteFailed("disk");
        config.willLoad(configOf(Optional.empty(), "wsl:A", "/gone"));
        config.willFailSave(error);
        launcher.willFailFor(
                command -> command.workingDirectory().equals(Optional.of("/gone")),
                new LaunchError.WorkingDirectoryNotFound("/gone"));

        // when
        StartedSession session = started(starter.start(chosen("wsl:A", true)));

        // then
        assertThat(session.notices())
                .containsExactly(
                        new StartNotice.StartDirMissing(wslPath("A", "/gone")),
                        new StartNotice.DefaultNotSaved(new ProfileId("wsl:A"), error));
    }

    @Test
    @DisplayName("셸이 하나도 없으면 기억을 켠 요청도 NoShellAvailable 이고 설정을 읽지 않는다")
    void noShellWithRememberDoesNotReadConfig() {
        // when
        var result = newStarter(new FakePlatformModule(List.of())).start(chosen("cmd", true));

        // then
        assertThat(result.error()).contains(new StartFailure.NoShellAvailable());
        assertThat(config.loadCalls()).isZero();
    }
}
