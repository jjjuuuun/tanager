package tanager.adapter.pty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tanager.application.port.out.LaunchCommand;
import tanager.application.port.out.LaunchCommand.EnvNames;
import tanager.application.port.out.LaunchError;
import tanager.application.port.out.OutputCollector;
import tanager.application.port.out.TerminalSize;
import tanager.application.port.out.TerminalStream;
import tanager.domain.shared.Result;

/** 앱 환경과 덮어쓴 환경이 대소문자만 다를 때 셸에 하나만 보이는지 ConPTY 로 확인한다(M3-01). */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
class Pty4jLauncherEnvironmentTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final Pty4jLauncher launcher = new Pty4jLauncher();
    private final List<TerminalStream> opened = new ArrayList<>();
    private final OutputCollector out = new OutputCollector();

    @AfterEach
    void closeAll() {
        opened.forEach(TerminalStream::close);
        opened.forEach(stream -> await().atMost(WAIT)
                .until(() -> stream.onExit().toCompletableFuture().isDone()));
    }

    @Test
    @DisplayName("상속과 다른 표기(PaTh)로 PATH 를 덮어쓰고 cmd 를 띄우면 set path 결과에 그 한 줄만 나온다")
    void overriddenPathAppearsOnce() {
        // 한 줄 출력 뒤에 표식을 붙여, 끝난 시점을 알고 줄 수를 센다
        LaunchCommand command = new LaunchCommand(
                List.of(WindowsShells.cmdExe(), "/c", "set path & echo ENVDONE"),
                Optional.empty(),
                // 상속 환경의 표기(보통 Path)와 반드시 다르게 — 고치기 전 코드라면 두 줄이 나온다
                Map.of("PaTh", "C:\\Windows\\System32;C:\\Windows"),
                EnvNames.CASE_INSENSITIVE);
        Result<TerminalStream, LaunchError> result = launcher.launch(command, new TerminalSize(300, 30));
        assertThat(result.isOk()).as("기동 결과 %s", result).isTrue();
        TerminalStream stream = result.value().orElseThrow();
        opened.add(stream);
        stream.subscribe(out);
        stream.start();

        await().atMost(WAIT).until(() -> out.text().contains("ENVDONE"));

        long lines = out.text()
                .lines()
                // ConPTY 가 첫 줄 앞에 CSI · 제목 OSC 를 붙일 수 있다(ADR-0007) — 지우고 센다
                .map(line -> line.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "")
                        .replaceAll("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)", ""))
                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("path="))
                .count();
        assertThat(lines).as("set path 출력 %s", out.text()).isEqualTo(1);
        assertThat(out.text()).contains("PaTh=C:\\Windows\\System32;C:\\Windows");
    }
}
