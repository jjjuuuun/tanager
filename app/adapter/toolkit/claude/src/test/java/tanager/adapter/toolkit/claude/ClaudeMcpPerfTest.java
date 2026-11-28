package tanager.adapter.toolkit.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static tanager.adapter.toolkit.claude.ClaudeFixture.path;
import static tanager.adapter.toolkit.claude.Scans.names;
import static tanager.adapter.toolkit.claude.Scans.ok;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.out.ToolkitQuery;
import tanager.application.port.out.ToolkitScan;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.toolkit.ToolkitKind;
import tanager.domain.toolkit.ToolkitScope;

/**
 * 큰 {@code ~/.claude.json} 의 읽기 시간(#212 인수 조건 "50MB 를 1초 안에"). 벽시계 시간은 다른 빌드와 겹친 부하에 흔들리므로(#145 · #166)
 * {@code perf} 태그로 pre-push 에서 뺀다. 메모리를 만들지 않는다는 조건(할당 바이트)은 {@link ClaudeMcpTest} 가 늘 확인한다.
 *
 * <p>한 번 읽어 데운 뒤 잰다. JVM 의 첫 읽기는 Jackson 클래스 적재와 JIT 가 대부분이고(시험 JVM 에서는 JaCoCo 계측까지 겹쳐 수 초) 파일 크기와
 * 상관없다 — 앱에서는 시작 때 state.json 읽기로 이미 적재돼 있다. 데운 뒤의 크기 비례 비용은 수십 ms 다.
 */
@Tag("perf")
class ClaudeMcpPerfTest {

    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("Ubuntu");
    private static final WorkspacePath PROJECT = path(WSL, "/home/dev/app");
    private static final WorkspacePath HOME = path(WSL, "/home/dev");

    @TempDir
    Path tmp;

    @Test
    @DisplayName("50MB ~/.claude.json 의 scan 이 1초 안에 끝난다")
    void largeClaudeJsonUnderOneSecond() throws IOException {
        // given
        Path homeDir = tmp.resolve("home");
        Path projectDir = Files.createDirectories(tmp.resolve("project"));
        LargeClaudeJson.write(homeDir.resolve(".claude.json"), LargeClaudeJson.SIZE);
        ClaudeFixture fixture = new ClaudeFixture().mount(HOME, homeDir).mount(PROJECT, projectDir);
        ToolkitQuery query = new ToolkitQuery(PROJECT, Optional.of(HOME), fixture.hostFunction());
        ClaudeToolkitProvider provider = new ClaudeToolkitProvider();

        ok(provider.scan(query));

        // when
        long started = System.nanoTime();
        ToolkitScan scan = ok(provider.scan(query));
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        // then
        assertThat(names(scan, ToolkitKind.MCP_SERVER, ToolkitScope.LOCAL)).containsExactly("db");
        System.out.println("50MB ~/.claude.json scan: " + took.toMillis() + " ms");
        assertThat(took).isLessThan(Duration.ofSeconds(1));
    }
}
