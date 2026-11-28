package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.RecordingVisits;
import tanager.application.port.in.StartFailure;
import tanager.application.port.in.StartNotice;
import tanager.application.port.in.StartRequest;
import tanager.application.port.in.StartedSession;
import tanager.application.port.out.FakeDirectoryProbe;
import tanager.application.port.out.FakePlatformModule;
import tanager.application.port.out.FakePlatformModule.LaunchRequest;
import tanager.application.port.out.FakeScheduler;
import tanager.application.port.out.InMemoryConfigRepository;
import tanager.application.port.out.LaunchError;
import tanager.application.port.out.ScriptedPtyLauncher;
import tanager.application.port.out.TerminalSize;
import tanager.application.state.AppState;
import tanager.application.state.StateActor;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.SessionState;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.WorkingDir;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;

/** 복원한 창을 저장된 경로에서 띄울 때 — 사라진 경로는 홈에서 띄우고 알린다(SDD §6.2, #179). */
class SessionStarterRestoreTest {
    private static final ShellEnvironment UBUNTU_ENV = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellProfile BASH = profile("wsl:Ubuntu", ShellKind.BASH, UBUNTU_ENV);
    private static final ShellProfile POWERSHELL = profile("powershell", ShellKind.WINDOWS_POWERSHELL, WIN);
    private static final TerminalSize SIZE = new TerminalSize(100, 30);
    private static final PaneId P3 = new PaneId("p3");
    private static final WorkspacePath GONE =
            WorkspacePath.of(UBUNTU_ENV, "/home/me/gone").value().orElseThrow();
    private static final WorkspacePath WIN_GONE =
            WorkspacePath.of(WIN, "C:\\gone").value().orElseThrow();

    private final FakeDirectoryProbe probe = new FakeDirectoryProbe();
    private final FakePlatformModule platform =
            new FakePlatformModule(List.of(BASH, POWERSHELL)).withDirectories(probe);
    private final InMemoryConfigRepository config = new InMemoryConfigRepository();
    private final ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();
    private final StateActor state =
            new StateActor(AppState.initial(WorkHistory.empty(HistoryPolicy.DEFAULT)), Runnable::run);
    private final SessionStarter starter = new SessionStarter(
            new ProfileCatalog(platform),
            config,
            platform,
            new IntegrationStatus(platform),
            launcher,
            new DefaultProfileMemory(config),
            state,
            new RecordingVisits(),
            new StartupCommands(new FakeScheduler(), Runnable::run, state));

    private static ShellProfile profile(String id, ShellKind kind, ShellEnvironment env) {
        return new ShellProfile(
                new ProfileId(id), id, kind, env, new LaunchTemplate(id + ".exe", List.of()), IntegrationMode.NONE);
    }

    private static StartRequest restored(ShellProfile profile, WorkspacePath dir) {
        return new StartRequest(P3, Optional.of(profile.id()), false, Optional.of(new WorkingDir.At(dir)), SIZE);
    }

    private static StartedSession started(Result<StartedSession, StartFailure> result) {
        assertThat(result.isOk()).as("시작 결과 %s", result).isTrue();
        return result.value().orElseThrow();
    }

    @Test
    @DisplayName("WSL 경로가 없으면 기동 전에 알아 홈에서 띄우고 MissingDirectory 로 알린다")
    void missingWslPathStartsAtHome() {
        // given
        probe.missing(GONE);

        // when
        StartedSession session = started(starter.start(restored(BASH, GONE)));

        // then wsl.exe 는 없는 --cd 를 받으면 / 에서 뜬다(#53) — 그 경로로 띄우지 않는다
        assertThat(platform.launchRequests()).containsExactly(new LaunchRequest(BASH, new WorkingDir.Home()));
        assertThat(session.notices()).containsExactly(new StartNotice.MissingDirectory(GONE));
        assertThat(session.session().state())
                .isEqualTo(new SessionState.Running(session.stream().pid(), new WorkingDir.Home()));
        assertThat(probe.calls()).containsExactly(new FakeDirectoryProbe.Call(GONE, Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("WSL 존재 확인이 1초를 넘기면(모름) 확인 없이 그 경로로 띄우고 알리지 않는다")
    void unknownWslPathLaunchesAsIs() {
        // given
        probe.unknown(GONE);

        // when
        StartedSession session = started(starter.start(restored(BASH, GONE)));

        // then
        assertThat(platform.launchRequests()).containsExactly(new LaunchRequest(BASH, new WorkingDir.At(GONE)));
        assertThat(session.notices()).isEmpty();
    }

    @Test
    @DisplayName("있는 WSL 경로는 그대로 띄운다")
    void existingWslPathLaunchesThere() {
        // when
        StartedSession session = started(starter.start(restored(BASH, GONE)));

        // then
        assertThat(platform.launchRequests()).containsExactly(new LaunchRequest(BASH, new WorkingDir.At(GONE)));
        assertThat(session.notices()).isEmpty();
    }

    @Test
    @DisplayName("없는 Windows 경로는 미리 확인하지 않고, 기동이 경로 없음을 알리면 홈에서 다시 띄우고 같은 알림을 준다")
    void missingWindowsPathRetriesAtHome() {
        // given
        launcher.willFailFor(
                command -> command.workingDirectory().equals(Optional.of("C:\\gone")),
                new LaunchError.WorkingDirectoryNotFound("C:\\gone"));

        // when
        StartedSession session = started(starter.start(restored(POWERSHELL, WIN_GONE)));

        // then
        assertThat(probe.calls()).isEmpty();
        assertThat(platform.launchRequests())
                .containsExactly(
                        new LaunchRequest(POWERSHELL, new WorkingDir.At(WIN_GONE)),
                        new LaunchRequest(POWERSHELL, new WorkingDir.Home()));
        assertThat(session.notices()).containsExactly(new StartNotice.MissingDirectory(WIN_GONE));
    }

    @Test
    @DisplayName("저장된 프로파일이 탐지 목록에 없으면 셸 선택 화면(ChoiceRequired)이고 아무것도 띄우지 않는다")
    void missingProfileRequiresChoice() {
        // when
        var result = starter.start(new StartRequest(
                P3, Optional.of(new ProfileId("wsl:Old")), false, Optional.of(new WorkingDir.At(GONE)), SIZE));

        // then
        assertThat(result.error()).containsInstanceOf(StartFailure.ChoiceRequired.class);
        assertThat(platform.launchRequests()).isEmpty();
        assertThat(probe.calls()).isEmpty();
    }
}
