package dev.tanager.adapter.pty;

import dev.tanager.application.port.out.LaunchCommand;
import dev.tanager.application.port.out.LaunchCommand.EnvNames;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Windows 통합 테스트가 쓰는 셸 명령 모음. */
final class WindowsShells {
    static final String POWERSHELL = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    static final String WSL = "C:\\Windows\\System32\\wsl.exe";

    private WindowsShells() {}

    static String cmdExe() {
        String comSpec = System.getenv("ComSpec");
        return comSpec != null && !comSpec.isBlank() ? comSpec : "C:\\Windows\\System32\\cmd.exe";
    }

    static LaunchCommand cmd() {
        return new LaunchCommand(List.of(cmdExe()), Optional.empty(), Map.of(), EnvNames.CASE_INSENSITIVE);
    }

    static LaunchCommand cmdRunning(String commandLine) {
        return new LaunchCommand(
                List.of(cmdExe(), "/c", commandLine), Optional.empty(), Map.of(), EnvNames.CASE_INSENSITIVE);
    }

    static LaunchCommand powershell() {
        return new LaunchCommand(
                List.of(POWERSHELL, "-NoLogo", "-NoProfile"), Optional.empty(), Map.of(), EnvNames.CASE_INSENSITIVE);
    }

    static byte[] line(String text) {
        return (text + "\r").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
