package dev.tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.SessionError;
import dev.tanager.domain.session.SessionState;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.TerminalSession;
import dev.tanager.domain.session.WorkingDir;
import dev.tanager.domain.shared.Result;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionStarterTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellProfile WSL_A = profile("wsl:A", ShellKind.BASH, new ShellEnvironment.Wsl("A"));
    private static final ShellProfile WSL_B = profile("wsl:B", ShellKind.BASH, new ShellEnvironment.Wsl("B"));
    private static final ShellProfile POWERSHELL = profile("powershell", ShellKind.WINDOWS_POWERSHELL, WIN);
    private static final ShellProfile CMD = profile("cmd", ShellKind.CMD, WIN);
    private static final TerminalSize SIZE = new TerminalSize(100, 30);

    private final FakePlatformModule platform = new FakePlatformModule(List.of(WSL_A, WSL_B, POWERSHELL, CMD));
    private final InMemoryConfigRepository config = new InMemoryConfigRepository();
    private final ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();
    private final SessionStarter starter = newStarter(platform);

    private SessionStarter newStarter(FakePlatformModule fakePlatform) {
        return new SessionStarter(new ProfileCatalog(fakePlatform), config, fakePlatform, launcher);
    }

    private static ShellProfile profile(String id, ShellKind kind, ShellEnvironment env) {
        return new ShellProfile(
                new ProfileId(id), id, kind, env, new LaunchTemplate(id + ".exe", List.of()), IntegrationMode.NONE);
    }

    private static StartRequest request() {
        return new StartRequest(Optional.empty(), Optional.empty(), SIZE);
    }

    private static StartRequest requestFor(String profileId) {
        return new StartRequest(Optional.of(new ProfileId(profileId)), Optional.empty(), SIZE);
    }

    private static WorkspacePath winPath(String raw) {
        return WorkspacePath.of(WIN, raw).value().orElseThrow();
    }

    private static WorkspacePath wslPath(String distro, String raw) {
        return WorkspacePath.of(new ShellEnvironment.Wsl(distro), raw).value().orElseThrow();
    }

    private static Result<LoadedConfig, ConfigError> configOf(Optional<ProfileId> defaultProfile, String... idAndDir) {
        Map<ProfileId, ProfileSettings> profiles = new java.util.HashMap<>();
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
    @DisplayName("설정이 없으면 첫 WSL 프로파일을 홈에서 띄우고 알림이 없다")
    void startsFirstWslAtHomeWithoutConfig() {
        // when
        StartedSession session = started(starter.start(request()));

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
    @DisplayName("기본 프로파일이 사라졌으면 첫 WSL 로 대체하고 알리며 설정은 저장하지 않는다")
    void replacesMissingDefaultProfileAndDoesNotSave() {
        // given
        config.willLoad(configOf(Optional.of(new ProfileId("wsl:Gone"))));

        // when
        StartedSession session = started(starter.start(request()));

        // then
        assertThat(session.session().profile()).isEqualTo(WSL_A);
        assertThat(session.notices())
                .containsExactly(new StartNotice.ProfileReplaced(new ProfileId("wsl:Gone"), new ProfileId("wsl:A")));
        assertThat(config.saves()).isEmpty();
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
        StartedSession session = started(starter.start(request()));

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
        StartedSession session = started(starter.start(request()));

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
        StartedSession session = started(starter.start(request()));

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
        StartedSession session = started(starter.start(request()));

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
        StartedSession session = started(starter.start(request()));

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
        started(starter.start(new StartRequest(Optional.of(new ProfileId("powershell")), Optional.of(dir), SIZE)));

        // then
        assertThat(platform.launchRequests()).containsExactly(new LaunchRequest(POWERSHELL, dir));
    }

    @Test
    @DisplayName("프로파일과 환경이 다른 경로를 요청하면 거부한다")
    void rejectsDirOfOtherEnvironment() {
        // given
        WorkingDir dir = new WorkingDir.At(winPath("C:\\work"));

        // when
        var result = starter.start(new StartRequest(Optional.of(new ProfileId("wsl:A")), Optional.of(dir), SIZE));

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
        var result = starter.start(request());

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

    // ---- 종료와 재시작 ----

    @Test
    @DisplayName("반환한 스트림은 아직 읽기를 시작하지 않았다")
    void returnedStreamIsNotStarted() {
        // given
        StartedSession session = started(starter.start(request()));
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
        StartedSession session = started(starter.start(request()));
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
        StartedSession first = started(starter.start(request()));
        launcher.streams().getFirst().exit(2);
        first.stream().start();
        TerminalSession exited = first.exited().toCompletableFuture().join();
        int loadsBefore = config.loadCalls();

        // when
        StartedSession second = started(starter.restart(exited, new TerminalSize(120, 40)));

        // then
        assertThat(second.session().profile()).isEqualTo(WSL_A);
        assertThat(second.session().state()).isInstanceOf(SessionState.Running.class);
        assertThat(platform.launchRequests())
                .containsExactly(
                        new LaunchRequest(WSL_A, new WorkingDir.Home()),
                        new LaunchRequest(WSL_A, new WorkingDir.Home()));
        assertThat(launcher.sizes()).containsExactly(SIZE, new TerminalSize(120, 40));
        assertThat(config.loadCalls()).isEqualTo(loadsBefore);
    }

    @Test
    @DisplayName("restart 는 마지막으로 알려진 cwd 에서 다시 띄운다")
    void restartStartsAtLastCwd() {
        // given
        StartedSession first = started(starter.start(request()));
        WorkspacePath last = wslPath("A", "/srv/app");
        TerminalSession exited = first.session()
                .cwdChanged(last)
                .value()
                .orElseThrow()
                .exited(0)
                .value()
                .orElseThrow();

        // when
        started(starter.restart(exited, SIZE));

        // then
        assertThat(platform.launchRequests().getLast()).isEqualTo(new LaunchRequest(WSL_A, new WorkingDir.At(last)));
    }

    @Test
    @DisplayName("끝나지 않은 세션의 restart 는 거부한다")
    void restartRejectsRunningSession() {
        // given
        StartedSession first = started(starter.start(request()));

        // when
        var result = starter.restart(first.session(), SIZE);

        // then
        assertThat(result.error())
                .contains(new StartFailure.Rejected(
                        new SessionError.IllegalTransition(first.session().state(), "restart")));
        assertThat(launcher.commands()).hasSize(1);
    }
}
