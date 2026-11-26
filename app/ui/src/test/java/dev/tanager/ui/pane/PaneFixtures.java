package dev.tanager.ui.pane;

import dev.tanager.application.port.in.StartFailure;
import dev.tanager.application.port.in.StartNotice;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.application.port.out.LaunchError;
import dev.tanager.application.port.out.TerminalStream;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.TerminalSession;
import dev.tanager.domain.session.WorkingDir;
import java.util.List;

/** 창 상태 · 컨트롤러 테스트가 같이 쓰는 세션 값. */
final class PaneFixtures {
    static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);

    static final ShellProfile UBUNTU = new ShellProfile(
            new ProfileId("wsl:Ubuntu"),
            "Ubuntu",
            ShellKind.BASH,
            new ShellEnvironment.Wsl("Ubuntu"),
            new LaunchTemplate("wsl.exe", List.of("-d", "Ubuntu")),
            IntegrationMode.OSC7);

    private PaneFixtures() {}

    static TerminalSession running(ShellProfile profile) {
        return TerminalSession.start(profile, new WorkingDir.Home())
                .value()
                .orElseThrow()
                .launched(10)
                .value()
                .orElseThrow();
    }

    static TerminalSession exited(int code) {
        return running(CMD).exited(code).value().orElseThrow();
    }

    /** {@code SessionStarter} 처럼 스트림이 끝나면 Exited 세션으로 완료되는 기동 결과. */
    static StartedSession started(TerminalStream stream, List<StartNotice> notices) {
        TerminalSession running = running(CMD);
        return new StartedSession(
                running,
                stream,
                stream.onExit().thenApply(code -> running.exited(code).value().orElseThrow()),
                notices);
    }

    static StartFailure.LaunchFailed launchFailed() {
        TerminalSession failed = TerminalSession.start(CMD, new WorkingDir.Home())
                .value()
                .orElseThrow()
                .launchFailed("boom")
                .value()
                .orElseThrow();
        return new StartFailure.LaunchFailed(failed, new LaunchError.StartFailed("boom"), List.of());
    }
}
