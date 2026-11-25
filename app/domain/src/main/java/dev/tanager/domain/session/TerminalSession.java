package dev.tanager.domain.session;

import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.shared.Result;
import java.util.Objects;
import java.util.Optional;

/**
 * 창 하나에서 도는 셸 프로세스 1개의 상태 기계. 전이는 새 세션을 돌려주고, 허용되지 않은 전이는
 * {@link SessionError.IllegalTransition} 으로 거부한다(원래 세션은 바뀌지 않는다).
 */
public record TerminalSession(ShellProfile profile, SessionState state) {

    public TerminalSession {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(state, "state");
        // 복원처럼 생성자를 직접 부르는 곳에서도 "경로는 프로파일의 환경" 불변식을 지킨다. 홈은 어느 환경에나 있다.
        pathOf(state).ifPresent(path -> {
            if (!path.env().equals(profile.env())) {
                throw new IllegalArgumentException("상태의 경로 환경이 프로파일과 다르다: " + path.env() + " ≠ " + profile.env());
            }
        });
    }

    private static Optional<WorkspacePath> pathOf(SessionState state) {
        return switch (state) {
            case SessionState.Starting s -> pathOf(s.dir());
            case SessionState.Running r -> pathOf(r.cwd());
            case SessionState.Exited e -> pathOf(e.lastCwd());
            case SessionState.Failed _ -> Optional.empty();
        };
    }

    private static Optional<WorkspacePath> pathOf(WorkingDir dir) {
        return switch (dir) {
            case WorkingDir.Home _ -> Optional.empty();
            case WorkingDir.At at -> Optional.of(at.path());
        };
    }

    /** {@code dir} 에서 시작하는 새 세션. 경로의 환경이 프로파일과 다르면 거부한다. */
    public static Result<TerminalSession, SessionError> start(ShellProfile profile, WorkingDir dir) {
        Objects.requireNonNull(dir, "dir");
        return switch (dir) {
            case WorkingDir.Home home -> Result.ok(new TerminalSession(profile, new SessionState.Starting(home)));
            case WorkingDir.At at ->
                sameEnvironment(profile, at.path())
                        .map(p -> new TerminalSession(profile, new SessionState.Starting(new WorkingDir.At(p))));
        };
    }

    /** 프로세스가 떴다: {@code Starting → Running}. */
    public Result<TerminalSession, SessionError> launched(long pid) {
        return switch (state) {
            case SessionState.Starting s -> Result.ok(with(new SessionState.Running(pid, s.dir())));
            case SessionState.Running _, SessionState.Exited _, SessionState.Failed _ -> illegal("launched");
        };
    }

    /** 프로세스를 띄우지 못했다: {@code Starting → Failed}. */
    public Result<TerminalSession, SessionError> launchFailed(String reason) {
        return switch (state) {
            case SessionState.Starting _ -> Result.ok(with(new SessionState.Failed(reason)));
            case SessionState.Running _, SessionState.Exited _, SessionState.Failed _ -> illegal("launchFailed");
        };
    }

    /** 셸 통합이 새 cwd 를 알렸다: {@code Running → Running(At cwd)}. 다른 환경의 경로는 거부한다. */
    public Result<TerminalSession, SessionError> cwdChanged(WorkspacePath cwd) {
        return switch (state) {
            case SessionState.Running r ->
                sameEnvironment(profile, cwd).map(c -> with(new SessionState.Running(r.pid(), new WorkingDir.At(c))));
            case SessionState.Starting _, SessionState.Exited _, SessionState.Failed _ -> illegal("cwdChanged");
        };
    }

    /** 셸이 끝났다: {@code Running → Exited}. 현재 cwd 가 마지막 경로가 된다. */
    public Result<TerminalSession, SessionError> exited(int exitCode) {
        return switch (state) {
            case SessionState.Running r -> Result.ok(with(new SessionState.Exited(exitCode, r.cwd())));
            case SessionState.Starting _, SessionState.Exited _, SessionState.Failed _ -> illegal("exited");
        };
    }

    /** 끝난 세션을 마지막 경로에서 다시 띄운다: {@code Exited → Starting}. */
    public Result<TerminalSession, SessionError> restart() {
        return switch (state) {
            case SessionState.Exited e -> Result.ok(with(new SessionState.Starting(e.lastCwd())));
            case SessionState.Starting _, SessionState.Running _, SessionState.Failed _ -> illegal("restart");
        };
    }

    private TerminalSession with(SessionState next) {
        return new TerminalSession(profile, next);
    }

    private Result<TerminalSession, SessionError> illegal(String event) {
        return Result.err(new SessionError.IllegalTransition(state, event));
    }

    private static Result<WorkspacePath, SessionError> sameEnvironment(ShellProfile profile, WorkspacePath path) {
        return profile.env().equals(path.env())
                ? Result.ok(path)
                : Result.err(new SessionError.EnvironmentMismatch(profile.env(), path.env()));
    }
}
