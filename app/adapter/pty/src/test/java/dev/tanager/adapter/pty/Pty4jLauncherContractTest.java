package dev.tanager.adapter.pty;

import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.application.port.out.PtyLauncher;
import dev.tanager.application.port.out.PtyLauncherContract;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** 실제 ConPTY 로 계약을 검증한다. Fake 와 같은 계약을 상속한다. */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
class Pty4jLauncherContractTest extends PtyLauncherContract {
    private final Pty4jLauncher launcher = new Pty4jLauncher();

    @Override
    protected PtyLauncher launcher() {
        return launcher;
    }

    @Override
    protected LaunchCommand printsThenExits(String text, int code) {
        return WindowsShells.cmdRunning("echo " + text + " & exit /b " + code);
    }

    @Override
    protected LaunchCommand interactive() {
        return WindowsShells.cmd();
    }

    @Override
    protected byte[] echoLine(String text) {
        return WindowsShells.line("echo " + text);
    }
}
