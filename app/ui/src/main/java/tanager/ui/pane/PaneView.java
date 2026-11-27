package tanager.ui.pane;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.application.port.in.StartFailure;
import tanager.application.port.in.StartNotice;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.session.SessionError;
import tanager.domain.session.SessionState;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.TerminalSession;
import tanager.domain.session.WorkingDir;
import tanager.ui.i18n.Msg;

/**
 * 창 상태를 화면 글자로 옮긴 것(순수 계산).
 *
 * @param header 창 머리줄 "셸 · 경로"
 * @param status 상태줄 왼쪽 "환경 · 셸 · 경로"
 * @param overlay 종료 띠 · 실패 화면의 본문
 * @param hint 누를 수 있는 키 안내
 * @param path 머리줄 끝의 경로(줄임 대상 · 툴팁). 세션이 없으면 비어 있다
 * @param warning 머리줄 오른쪽 경고(셸 통합 없음)
 */
record PaneView(
        String header,
        String status,
        Optional<String> overlay,
        Optional<String> hint,
        Optional<String> path,
        Optional<String> warning) {
    static final String STARTING = Msg.PANE_STARTING.text();
    static final String EXITED_HINT = Msg.PANE_HINT_EXITED.text();
    static final String RETRY_HINT = Msg.PANE_HINT_RETRY.text();
    static final String CLOSE_HINT = Msg.HINT_ESC_CLOSE.text();
    static final String PICKING_HEADER = Msg.PANE_PICKING_HEADER.text();
    static final String NO_INTEGRATION = Msg.PANE_NO_INTEGRATION.text();

    PaneView {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(overlay, "overlay");
        Objects.requireNonNull(hint, "hint");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(warning, "warning");
    }

    static PaneView of(PaneState state) {
        return switch (state) {
            case PaneState.Launching(Attempt attempt) ->
                plain(STARTING, attempt.knownProfile().map(PaneView::status).orElse(""));
            case PaneState.Running(var started) ->
                new PaneView(
                        header(started.session()),
                        sessionStatus(started.session()),
                        Optional.empty(),
                        Optional.empty(),
                        pathOf(started.session()),
                        integrationMissing(started.notices()) ? Optional.of(NO_INTEGRATION) : Optional.empty());
            case PaneState.Exited(TerminalSession session) ->
                new PaneView(
                        header(session),
                        sessionStatus(session),
                        Optional.of(Msg.PANE_EXIT_CODE.text(exitCode(session))),
                        Optional.of(EXITED_HINT),
                        pathOf(session),
                        Optional.empty());
            case PaneState.Failed failed -> failed(failed);
            case PaneState.Errored(String detail, Attempt retry) ->
                new PaneView(
                        retry.knownProfile().map(PaneView::shell).orElse("Tanager"),
                        retry.knownProfile().map(PaneView::status).orElse(""),
                        Optional.of(Msg.PANE_ERRORED.text(detail)),
                        Optional.of(RETRY_HINT),
                        Optional.empty(),
                        Optional.empty());
            case PaneState.Picking picking -> {
                PickerView picker = PickerView.of(picking);
                yield new PaneView(
                        PICKING_HEADER,
                        "",
                        picker.missing(),
                        Optional.of(picker.hint()),
                        Optional.empty(),
                        Optional.empty());
            }
            case PaneState.Closed _ -> plain("", "");
        };
    }

    /** 탭에 보일 이름: 프로파일 이름, 아직 모르면 앱 이름. */
    static String tabTitle(PaneState state) {
        return profileOf(state).map(ShellProfile::name).orElse("Tanager");
    }

    private static PaneView failed(PaneState.Failed failed) {
        return switch (failed.failure()) {
            case StartFailure.LaunchFailed launch ->
                new PaneView(
                        shell(launch.failed().profile()),
                        status(launch.failed().profile()),
                        Optional.of(Msg.PANE_LAUNCH_FAILED.text(launch.error().describe())),
                        Optional.of(RETRY_HINT),
                        Optional.empty(),
                        Optional.empty());
            case StartFailure.NoShellAvailable _ ->
                new PaneView(
                        "Tanager",
                        "",
                        Optional.of(Msg.PANE_NO_SHELL.text()),
                        Optional.of(CLOSE_HINT),
                        Optional.empty(),
                        Optional.empty());
            case StartFailure.Rejected rejected ->
                new PaneView(
                        "Tanager",
                        "",
                        Optional.of(Msg.PANE_REJECTED.text(describe(rejected.error()))),
                        Optional.of(CLOSE_HINT),
                        Optional.empty(),
                        Optional.empty());
            case StartFailure.ChoiceRequired _ -> throw new IllegalStateException("선택이 필요하다는 결과는 Failed 에 담기지 않는다");
        };
    }

    private static PaneView plain(String header, String status) {
        return new PaneView(header, status, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Optional<ShellProfile> profileOf(PaneState state) {
        return switch (state) {
            case PaneState.Launching(Attempt attempt) -> attempt.knownProfile();
            case PaneState.Running(var started) -> Optional.of(started.session().profile());
            case PaneState.Exited(TerminalSession session) -> Optional.of(session.profile());
            case PaneState.Failed(StartFailure.LaunchFailed launch, Attempt _) ->
                Optional.of(launch.failed().profile());
            case PaneState.Errored(String _, Attempt retry) -> retry.knownProfile();
            case PaneState.Failed _, PaneState.Picking _, PaneState.Closed _ -> Optional.empty();
        };
    }

    private static String header(TerminalSession session) {
        String shell = shell(session.profile());
        return pathOf(session).map(path -> shell + " · " + path).orElse(shell);
    }

    private static String sessionStatus(TerminalSession session) {
        String status = status(session.profile());
        return pathOf(session).map(path -> status + " · " + path).orElse(status);
    }

    private static Optional<String> pathOf(TerminalSession session) {
        return dirOf(session.state()).map(PaneView::shown);
    }

    private static boolean integrationMissing(List<StartNotice> notices) {
        return notices.stream().anyMatch(StartNotice.IntegrationUnavailable.class::isInstance);
    }

    private static Optional<WorkingDir> dirOf(SessionState state) {
        return switch (state) {
            case SessionState.Starting(WorkingDir dir) -> Optional.of(dir);
            case SessionState.Running(long _, WorkingDir cwd) -> Optional.of(cwd);
            case SessionState.Exited(int _, WorkingDir lastCwd) -> Optional.of(lastCwd);
            case SessionState.Failed _ -> Optional.empty();
        };
    }

    private static String shown(WorkingDir dir) {
        return switch (dir) {
            case WorkingDir.Home _ -> "~";
            case WorkingDir.At at -> at.path().path();
        };
    }

    /** "WSL · Ubuntu · bash" / "Windows · Windows PowerShell". */
    static String status(ShellProfile profile) {
        String env = switch (profile.env()) {
            case ShellEnvironment.Wsl(String distro) -> "WSL · " + distro;
            case ShellEnvironment.Windows _ -> "Windows";
        };
        return env + " · " + shell(profile);
    }

    private static String shell(ShellProfile profile) {
        return switch (profile.kind()) {
            case BASH -> "bash";
            case ZSH -> "zsh";
            case WINDOWS_POWERSHELL, CMD -> profile.name();
        };
    }

    private static int exitCode(TerminalSession session) {
        return switch (session.state()) {
            case SessionState.Exited exited -> exited.exitCode();
            case SessionState.Starting _, SessionState.Running _, SessionState.Failed _ ->
                throw new IllegalStateException("끝나지 않은 세션: " + session.state());
        };
    }

    private static String describe(SessionError error) {
        return switch (error) {
            case SessionError.IllegalTransition(SessionState from, String event) ->
                Msg.PANE_REJECTED_TRANSITION.text(from.getClass().getSimpleName(), event);
            case SessionError.EnvironmentMismatch _ -> Msg.PANE_REJECTED_ENVIRONMENT.text();
        };
    }
}
