package dev.tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jediterm.terminal.ui.JediTermWidget;
import dev.tanager.adapter.pty.Pty4jLauncher;
import dev.tanager.application.platform.PlatformSelection;
import dev.tanager.application.port.in.StartRequest;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.ui.font.TerminalFontSet;
import dev.tanager.ui.terminal.StreamTtyConnector;
import dev.tanager.ui.terminal.TanagerTermWidget;
import dev.tanager.ui.terminal.TerminalSettings;
import dev.tanager.ui.theme.JediTermColors;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import javax.swing.UIManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** DA1 응답으로 ConPTY 의 3초 지연이 사라졌는지 실제 PowerShell 5.1 로 확인한다(ADR-0011). */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
class PowerShellSmokeTest {
    @Test
    @DisplayName("PowerShell 이 1.5초 안에 프롬프트를 그리고 exit 2 는 1초 안에 종료 코드 2 로 끝난다")
    void promptAppearsQuicklyAndExitCodeArrives() throws Exception {
        // given
        UIManager.setLookAndFeel("dev.tanager.ui.theme.SoftDarkLaf");
        List<PlatformModule> candidates = ServiceLoader.load(PlatformModule.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        PlatformModule platform = PlatformSelection.select(candidates, TanagerApp.currentOs())
                .value()
                .orElseThrow();
        ShellProfile powershell = platform.shells().stream()
                .filter(p -> p.kind() == ShellKind.WINDOWS_POWERSHELL)
                .findFirst()
                .orElseThrow();
        Assembly assembly = Assembly.assemble(platform, new Pty4jLauncher());
        StartedSession started = assembly.sessions()
                .start(new StartRequest(Optional.of(powershell.id()), Optional.empty(), new TerminalSize(120, 30)))
                .value()
                .orElseThrow();
        JediTermWidget widget =
                new TanagerTermWidget(new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.current()));
        StreamTtyConnector connector = StreamTtyConnector.attach(started.stream(), "powershell");

        // when
        widget.setTtyConnector(connector);
        widget.start();
        started.stream().start();

        // then
        await().atMost(Duration.ofMillis(1500))
                .untilAsserted(() -> assertThat(widget.getTerminalTextBuffer().getScreenLines())
                        .contains("PS "));
        connector.write("exit 2\r");
        assertThat(started.exited()
                        .toCompletableFuture()
                        .get(1, java.util.concurrent.TimeUnit.SECONDS)
                        .state())
                .isInstanceOf(dev.tanager.domain.session.SessionState.Exited.class);
        assertThat(((dev.tanager.domain.session.SessionState.Exited)
                                started.exited().toCompletableFuture().join().state())
                        .exitCode())
                .isEqualTo(2);
        widget.close();
        connector.close();
    }
}
