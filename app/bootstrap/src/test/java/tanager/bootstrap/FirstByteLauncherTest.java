package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.LaunchCommand;
import tanager.application.port.out.LaunchError;
import tanager.application.port.out.OutputCollector;
import tanager.application.port.out.ScriptedPtyLauncher;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.application.port.out.TerminalSize;
import tanager.application.port.out.TerminalStream;

/** 첫 셸이 첫 바이트를 내는 때를 시작 시간 기록으로 넘긴다(#221). */
class FirstByteLauncherTest {
    private static final LaunchCommand SHELL =
            new LaunchCommand(List.of("fake-shell"), Optional.empty(), Map.of(), LaunchCommand.EnvNames.CASE_SENSITIVE);

    private final ScriptedPtyLauncher shells = new ScriptedPtyLauncher();
    private final AtomicInteger firstBytes = new AtomicInteger();
    private final FirstByteLauncher launcher = new FirstByteLauncher(shells, firstBytes::incrementAndGet);

    @Test
    @DisplayName("어느 셸이든 처음 나온 바이트에서 한 번만 알리고 출력은 그대로 전한다")
    void notifiesOnceOnFirstByte() {
        // given
        TerminalStream first =
                launcher.launch(SHELL, TerminalSize.DEFAULT).value().orElseThrow();
        TerminalStream second =
                launcher.launch(SHELL, TerminalSize.DEFAULT).value().orElseThrow();
        OutputCollector output = new OutputCollector();
        first.subscribe(output);
        first.start();
        second.start();
        assertThat(firstBytes).hasValue(0);

        // when
        ScriptedTerminalStream firstShell = shells.streams().get(0);
        firstShell.emit("C:\\> ");
        firstShell.emit("dir");
        shells.streams().get(1).emit("$ ");

        // then
        assertThat(firstBytes).hasValue(1);
        assertThat(output.text()).isEqualTo("C:\\> dir");
    }

    @Test
    @DisplayName("첫 바이트를 받은 뒤의 셸에는 구독을 붙이지 않는다")
    void stopsWatchingAfterFirstByte() {
        // given
        TerminalStream first =
                launcher.launch(SHELL, TerminalSize.DEFAULT).value().orElseThrow();
        first.start();
        shells.streams().get(0).emit("> ");

        // when
        launcher.launch(SHELL, TerminalSize.DEFAULT);

        // then
        assertThat(shells.streams().get(0).subscriberCount()).isZero();
        assertThat(shells.streams().get(1).subscriberCount()).isZero();
    }

    @Test
    @DisplayName("셸을 띄우지 못하면 실패를 그대로 돌려준다")
    void passesFailureThrough() {
        // given
        LaunchError missing = new LaunchError.ExecutableNotFound("fake-shell");
        shells.willFail(missing);

        // when
        var result = launcher.launch(SHELL, TerminalSize.DEFAULT);

        // then
        assertThat(result.error()).contains(missing);
        assertThat(firstBytes).hasValue(0);
    }
}
