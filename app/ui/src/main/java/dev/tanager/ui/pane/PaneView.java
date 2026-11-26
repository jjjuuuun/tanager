package dev.tanager.ui.pane;

import dev.tanager.application.port.in.StartFailure;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.SessionError;
import dev.tanager.domain.session.SessionState;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.TerminalSession;
import dev.tanager.domain.session.WorkingDir;
import java.util.Objects;
import java.util.Optional;

/**
 * 창 상태를 화면 글자로 옮긴 것(순수 계산).
 *
 * @param header 창 머리줄 "셸 · 경로"
 * @param status 상태줄 왼쪽 "환경 · 셸"
 * @param overlay 종료 띠 · 실패 화면의 본문
 * @param hint 누를 수 있는 키 안내
 */
record PaneView(String header, String status, Optional<String> overlay, Optional<String> hint) {
    static final String STARTING = "셸을 시작하는 중…";
    static final String EXITED_HINT = "Enter 다시 시작 · Tab 다른 셸 · Esc 닫기";
    static final String RETRY_HINT = "Enter 다시 시도 · Tab 다른 셸 · Esc 닫기";
    static final String CLOSE_HINT = "Esc 닫기";
    static final String PICKING_HEADER = "셸 선택";

    PaneView {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(overlay, "overlay");
        Objects.requireNonNull(hint, "hint");
    }

    static PaneView of(PaneState state) {
        return switch (state) {
            case PaneState.Launching(Attempt attempt) ->
                plain(STARTING, attempt.knownProfile().map(PaneView::status).orElse(""));
            case PaneState.Running(var started) ->
                plain(header(started.session()), status(started.session().profile()));
            case PaneState.Exited(TerminalSession session) ->
                new PaneView(
                        header(session),
                        status(session.profile()),
                        Optional.of("종료 코드 " + exitCode(session)),
                        Optional.of(EXITED_HINT));
            case PaneState.Failed failed -> failed(failed);
            case PaneState.Errored(String detail, Attempt retry) ->
                new PaneView(
                        retry.knownProfile().map(PaneView::shell).orElse("Tanager"),
                        retry.knownProfile().map(PaneView::status).orElse(""),
                        Optional.of("셸을 시작하다 예상하지 못한 오류가 났다 — " + detail),
                        Optional.of(RETRY_HINT));
            case PaneState.Picking picking -> {
                PickerView picker = PickerView.of(picking);
                yield new PaneView(PICKING_HEADER, "", picker.missing(), Optional.of(picker.hint()));
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
                        Optional.of("셸을 시작하지 못했다 — " + launch.error().describe()),
                        Optional.of(RETRY_HINT));
            case StartFailure.NoShellAvailable _ ->
                new PaneView(
                        "Tanager",
                        "",
                        Optional.of("띄울 수 있는 셸(WSL · Windows PowerShell · cmd)을 찾지 못했다 — 앱을 다시 시작해 주세요"),
                        Optional.of(CLOSE_HINT));
            case StartFailure.Rejected rejected ->
                new PaneView(
                        "Tanager",
                        "",
                        Optional.of("세션을 시작할 수 없다 — " + describe(rejected.error())),
                        Optional.of(CLOSE_HINT));
            case StartFailure.ChoiceRequired _ -> throw new IllegalStateException("선택이 필요하다는 결과는 Failed 에 담기지 않는다");
        };
    }

    private static PaneView plain(String header, String status) {
        return new PaneView(header, status, Optional.empty(), Optional.empty());
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
        return dirOf(session.state()).map(dir -> shell + " · " + path(dir)).orElse(shell);
    }

    private static Optional<WorkingDir> dirOf(SessionState state) {
        return switch (state) {
            case SessionState.Starting(WorkingDir dir) -> Optional.of(dir);
            case SessionState.Running(long _, WorkingDir cwd) -> Optional.of(cwd);
            case SessionState.Exited(int _, WorkingDir lastCwd) -> Optional.of(lastCwd);
            case SessionState.Failed _ -> Optional.empty();
        };
    }

    private static String path(WorkingDir dir) {
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
                "지금 상태(" + from.getClass().getSimpleName() + ")에서 " + event + " 를 할 수 없다";
            case SessionError.EnvironmentMismatch _ -> "시작 경로의 환경이 셸의 환경과 다르다";
        };
    }
}
