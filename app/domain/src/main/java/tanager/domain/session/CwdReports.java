package tanager.domain.session;

import java.util.Objects;
import tanager.domain.history.PathError;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;

/** {@link CwdReport} 를 세션 환경의 {@link WorkspacePath} 로 해석한다. */
public final class CwdReports {

    private CwdReports() {}

    /**
     * 보고를 그 환경의 작업 경로로. OSC 7 은 WSL 에서만, OSC 9;9 는 Windows 에서만 받는다. 환경이 맞지 않으면 그 환경의 절대 경로가
     * 아니므로 {@link PathError.NotAbsolute} 다.
     */
    public static Result<WorkspacePath, PathError> toPath(CwdReport report, ShellEnvironment env) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(env, "env");
        return switch (report) {
            case CwdReport.FileUri uri ->
                switch (env) {
                    case ShellEnvironment.Wsl _ -> WorkspacePath.of(env, uri.path());
                    case ShellEnvironment.Windows _ -> Result.err(new PathError.NotAbsolute(uri.path()));
                };
            case CwdReport.WindowsPath win ->
                switch (env) {
                    case ShellEnvironment.Windows _ -> WorkspacePath.of(env, win.path());
                    case ShellEnvironment.Wsl _ -> Result.err(new PathError.NotAbsolute(win.path()));
                };
        };
    }
}
