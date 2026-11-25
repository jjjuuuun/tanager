package dev.tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fake 가 계약을 지키는지(= 다른 테스트가 Fake 를 믿어도 되는지) 확인한다. */
class ScriptedPtyLauncherContractTest extends PtyLauncherContract {
    private static final LaunchCommand COMMAND = new LaunchCommand(List.of("fake-shell"), Optional.empty(), Map.of());

    private final ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();

    @Override
    protected PtyLauncher launcher() {
        return launcher;
    }

    @Override
    protected LaunchCommand printsThenExits(String text, int code) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int half = Math.max(1, bytes.length / 2);
        launcher.willScript(stream -> stream.whenStarted(s -> {
            // 조각 둘로 나눠 보내 "예외 던진 구독자는 그 뒤로 불리지 않는다" 를 의미 있게 만든다
            s.emit(Arrays.copyOfRange(bytes, 0, half));
            s.emit(Arrays.copyOfRange(bytes, half, bytes.length));
            s.exit(code);
        }));
        return COMMAND;
    }

    @Override
    protected LaunchCommand interactive() {
        launcher.willScript(ScriptedTerminalStream::echoWrites);
        return COMMAND;
    }

    @Override
    protected byte[] echoLine(String text) {
        return utf8(text + "\n");
    }

    @Test
    @DisplayName("Fake 는 받은 명령과 초기 크기를 기록하고 실패를 설정할 수 있다")
    void recordsRequestsAndCanFail() {
        // given
        LaunchError error = new LaunchError.ExecutableNotFound("fake-shell");
        launcher.willFail(error);

        // when
        var result = launcher.launch(COMMAND, new TerminalSize(90, 25));

        // then
        assertThat(result.error()).contains(error);
        assertThat(launcher.commands()).containsExactly(COMMAND);
        assertThat(launcher.sizes()).containsExactly(new TerminalSize(90, 25));
    }

    @Test
    @DisplayName("start 전에 emit 한 출력은 start 때 첫 바이트부터 전달된다")
    void outputEmittedBeforeStartIsDeliveredAtStart() {
        // given
        ScriptedTerminalStream stream = new ScriptedTerminalStream(1);
        stream.emit("early");
        OutputCollector out = new OutputCollector();
        stream.subscribe(out);

        // when
        stream.start();

        // then
        assertThat(out.text()).isEqualTo("early");
    }

    @Test
    @DisplayName("write 와 resize 와 close 호출이 기록된다")
    void recordsWritesResizesAndCloses() {
        // given
        ScriptedTerminalStream stream = new ScriptedTerminalStream(1);
        stream.start();

        // when
        stream.write(new byte[] {1, 2});
        stream.resize(new TerminalSize(100, 30));
        stream.close();
        stream.close();

        // then
        assertThat(stream.written()).hasSize(1);
        assertThat(stream.written().get(0)).containsExactly(1, 2);
        assertThat(stream.resizes()).containsExactly(new TerminalSize(100, 30));
        assertThat(stream.closeCalls()).isEqualTo(2);
    }
}
