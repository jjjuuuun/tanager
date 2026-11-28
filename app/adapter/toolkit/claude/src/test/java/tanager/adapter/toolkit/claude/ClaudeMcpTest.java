package tanager.adapter.toolkit.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static tanager.adapter.toolkit.claude.ClaudeFixture.path;
import static tanager.adapter.toolkit.claude.Scans.item;
import static tanager.adapter.toolkit.claude.Scans.names;
import static tanager.adapter.toolkit.claude.Scans.ok;
import static tanager.adapter.toolkit.claude.Scans.plain;
import static tanager.adapter.toolkit.claude.Scans.source;

import com.sun.management.ThreadMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.out.ScanStamp;
import tanager.application.port.out.ToolkitQuery;
import tanager.application.port.out.ToolkitScan;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.toolkit.Attribute;
import tanager.domain.toolkit.AttributeValue;
import tanager.domain.toolkit.SourceRef;
import tanager.domain.toolkit.ToolkitItem;
import tanager.domain.toolkit.ToolkitKind;
import tanager.domain.toolkit.ToolkitScope;
import tanager.domain.toolkit.ToolkitWarning;

/** MCP 서버 — {@code .mcp.json} · {@code ~/.claude.json} · {@code managed-mcp.json}(#212). 픽스처의 비밀값은 모두 합성이다. */
class ClaudeMcpTest {

    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();

    private static final WorkspacePath PROJECT = path(WSL, "/home/dev/app");
    private static final WorkspacePath HOME = path(WSL, "/home/dev");
    private static final WorkspacePath MANAGED = path(WSL, "/etc/claude-code");

    private static final ToolkitKind MCP = ToolkitKind.MCP_SERVER;
    private static final String M = SecretMask.MASK;

    @TempDir
    Path tmp;

    private Path projectDir;
    private Path homeDir;
    private ClaudeFixture fixture;

    @BeforeEach
    void setUp() {
        projectDir = tmp.resolve("project");
        homeDir = tmp.resolve("home");
        fixture = new ClaudeFixture().copy("mcp/home", homeDir, HOME).copy("mcp/project", projectDir, PROJECT);
    }

    private ToolkitQuery query() {
        return new ToolkitQuery(PROJECT, Optional.of(HOME), fixture.hostFunction());
    }

    private ToolkitScan scan() {
        return ok(new ClaudeToolkitProvider().scan(query()));
    }

    private static ToolkitScan scan(ToolkitQuery query) {
        return ok(new ClaudeToolkitProvider().scan(query));
    }

    private static Attribute masked(String key) {
        return new Attribute(key, new AttributeValue.Masked());
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName(
            ".mcp.json 의 stdio 서버 github 는 MCP_SERVER · PROJECT · type=stdio · command=npx -y @mcp/github · env 키는 Masked 다")
    void projectStdio() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem github = item(scan, MCP, ToolkitScope.PROJECT, "github");
        assertThat(github.source()).isEqualTo(source(PROJECT, ".mcp.json"));
        assertThat(github.attributes())
                .containsExactly(
                        plain("type", "stdio"), plain("command", "npx -y @mcp/github"), masked("env.GITHUB_TOKEN"));
        assertThat(github.description()).isEmpty();
    }

    @Test
    @DisplayName("http 서버의 url 은 스킴 · 호스트 · 포트만이고 headers 키는 Masked 다")
    void projectHttp() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem api = item(scan, MCP, ToolkitScope.PROJECT, "api");
        assertThat(api.attributes())
                .containsExactly(
                        plain("type", "http"),
                        plain("url", "https://api.example.com:8443"),
                        masked("headers.Authorization"));
    }

    @Test
    @DisplayName("args 의 --api-key 다음 값은 가리고 --port 3000 은 보인다")
    void argsRedacted() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem keyed = item(scan, MCP, ToolkitScope.PROJECT, "keyed");
        assertThat(keyed.attributes())
                .containsExactly(
                        plain("type", "stdio"), plain("command", "node server.js --api-key " + M + " --port 3000"));
    }

    @Test
    @DisplayName("~/.claude.json 의 projects[이 경로] 는 LOCAL, 맨 위 mcpServers 는 USER 이고 다른 프로젝트의 서버는 없다")
    void claudeJsonScopes() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem local = item(scan, MCP, ToolkitScope.LOCAL, "db");
        assertThat(local.source()).isEqualTo(source(HOME, ".claude.json"));
        assertThat(local.attributes())
                .containsExactly(plain("type", "stdio"), plain("command", "local-db --password " + M));
        assertThat(names(scan, MCP, ToolkitScope.USER)).containsExactly("db", "notes");
        assertThat(item(scan, MCP, ToolkitScope.USER, "notes").attributes())
                .containsExactly(plain("type", "sse"), plain("url", "https://notes.example.com"));
        assertThat(scan.items()).extracting(ToolkitItem::name).doesNotContain("other");
    }

    @Test
    @DisplayName("같은 이름 db 가 LOCAL · PROJECT · USER 에 있으면 PROJECT · USER 가 가려진다")
    void shadowing() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(item(scan, MCP, ToolkitScope.LOCAL, "db").shadowed()).isFalse();
        assertThat(item(scan, MCP, ToolkitScope.PROJECT, "db").shadowed()).isTrue();
        assertThat(item(scan, MCP, ToolkitScope.USER, "db").shadowed()).isTrue();
        assertThat(item(scan, MCP, ToolkitScope.PROJECT, "github").shadowed()).isFalse();
    }

    @Test
    @DisplayName("managed-mcp.json 이 있으면 MANAGED 항목이 맨 앞이고 같은 이름의 다른 범위를 모두 가린다")
    void managed() {
        // given
        fixture.copy("mcp/managed", tmp.resolve("managed"), MANAGED);

        // when
        ToolkitScan scan = scan();

        // then
        List<ToolkitItem> mcp =
                scan.items().stream().filter(i -> i.kind() == MCP).toList();
        assertThat(mcp.getFirst().scope()).isEqualTo(ToolkitScope.MANAGED);
        ToolkitItem db = item(scan, MCP, ToolkitScope.MANAGED, "db");
        assertThat(db.source()).isEqualTo(new SourceRef(path(WSL, "/etc/claude-code/managed-mcp.json")));
        assertThat(db.shadowed()).isFalse();
        assertThat(item(scan, MCP, ToolkitScope.LOCAL, "db").shadowed()).isTrue();
        assertThat(item(scan, MCP, ToolkitScope.MANAGED, "corp").attributes())
                .containsExactly(
                        plain("type", "http"), plain("url", "https://mcp.corp.example.com"), masked("headersHelper"));
    }

    @Test
    @DisplayName("Windows 는 projects 키를 대소문자 무시 · 구분자 \\ · / 둘 다로 맞춘다")
    void windowsProjectKey() throws IOException {
        // given
        WorkspacePath project = path(WINDOWS, "c:\\work\\app");
        WorkspacePath home = path(WINDOWS, "C:\\Users\\dev");
        Path winProject = Files.createDirectories(tmp.resolve("win-project"));
        Path winHome = Files.createDirectories(tmp.resolve("win-home"));
        write(winHome.resolve(".claude.json"), """
                {"projects": {
                  "C:\\\\Work\\\\App": {"mcpServers": {"back": {"command": "b"}}},
                  "C:/WORK/APP": {"mcpServers": {"forward": {"command": "f"}}},
                  "C:\\\\Work\\\\Other": {"mcpServers": {"other": {"command": "o"}}}
                }}
                """);
        ClaudeFixture win = new ClaudeFixture().mount(project, winProject).mount(home, winHome);

        // when
        ToolkitScan scan = scan(new ToolkitQuery(project, Optional.of(home), win.hostFunction()));

        // then
        assertThat(names(scan, MCP, ToolkitScope.LOCAL)).containsExactly("back", "forward");
        assertThat(item(scan, MCP, ToolkitScope.LOCAL, "back").source())
                .isEqualTo(new SourceRef(path(WINDOWS, "C:\\Users\\dev\\.claude.json")));
    }

    @Test
    @DisplayName("WSL 은 projects 키의 대소문자를 구분한다")
    void wslProjectKeyCaseSensitive() throws IOException {
        // given
        write(homeDir.resolve(".claude.json"), """
                {"projects": {"/home/dev/App": {"mcpServers": {"upper": {"command": "u"}}}}}
                """);

        // when
        ToolkitScan scan = scan();

        // then
        assertThat(names(scan, MCP, ToolkitScope.LOCAL)).isEmpty();
    }

    @Test
    @DisplayName(".mcp.json 이 깨졌으면 Malformed(.mcp.json, 줄)이고 ~/.claude.json 의 서버는 보인다")
    void malformed() {
        // given
        WorkspacePath broken = path(WSL, "/home/dev/broken");
        fixture.copy("mcp/broken", tmp.resolve("broken"), broken);

        // when
        ToolkitScan scan = scan(new ToolkitQuery(broken, Optional.of(HOME), fixture.hostFunction()));

        // then
        assertThat(scan.warnings())
                .containsExactly(new ToolkitWarning.Malformed(source(broken, ".mcp.json"), OptionalInt.of(4)));
        assertThat(names(scan, MCP, ToolkitScope.PROJECT)).isEmpty();
        assertThat(names(scan, MCP, ToolkitScope.USER)).containsExactly("db", "notes");
    }

    @Test
    @DisplayName("MCP 파일이 하나도 없으면 MCP 항목도 경고도 없다")
    void noFiles() throws IOException {
        // given
        WorkspacePath empty = path(WSL, "/srv/empty");
        ClaudeFixture only = new ClaudeFixture().mount(empty, Files.createDirectories(tmp.resolve("empty")));

        // when
        ToolkitScan scan = scan(new ToolkitQuery(empty, Optional.empty(), only.hostFunction()));

        // then
        assertThat(scan.items()).isEmpty();
        assertThat(scan.warnings()).isEmpty();
    }

    @Test
    @DisplayName("mcpServers 가 객체가 아니거나 서버 값이 객체가 아니면 그 자리만 건너뛴다")
    void unexpectedShapes() throws IOException {
        // given
        write(projectDir.resolve(".mcp.json"), """
                {"mcpServers": {"odd": "not an object", "ok": {"command": "fine", "args": [1, true, {"x": 1}]}}}
                """);
        write(homeDir.resolve(".claude.json"), """
                {"mcpServers": ["not", "an", "object"], "projects": {"/home/dev/app": 3}}
                """);

        // when
        ToolkitScan scan = scan();

        // then
        assertThat(scan.items().stream().filter(i -> i.kind() == MCP).map(ToolkitItem::name))
                .containsExactly("ok");
        assertThat(item(scan, MCP, ToolkitScope.PROJECT, "ok").attributes())
                .containsExactly(plain("type", "stdio"), plain("command", "fine 1 true"));
        assertThat(scan.warnings()).isEmpty();
    }

    @Test
    @DisplayName("지문 입력에 .mcp.json · ~/.claude.json · managed-mcp.json 이 들어 있고 .mcp.json 을 고치면 stamp 가 달라진다")
    void stamp() throws IOException {
        // given
        Path managedDir = tmp.resolve("missing-managed");
        fixture.mount(MANAGED, managedDir);
        ClaudeToolkitProvider provider = new ClaudeToolkitProvider();
        ScanStamp before = provider.stamp(query()).orElseThrow();

        // when
        Path mcpJson = projectDir.resolve(".mcp.json");
        Files.setLastModifiedTime(mcpJson, FileTime.from(Instant.parse("2030-01-01T00:00:00Z")));
        ScanStamp after = provider.stamp(query()).orElseThrow();

        // then
        assertThat(before.inputs())
                .containsKeys(mcpJson, homeDir.resolve(".claude.json"), managedDir.resolve("managed-mcp.json"))
                .isNotEqualTo(after.inputs());
        assertThat(ok(provider.scan(query())).stamp()).contains(after);
    }

    @Test
    @DisplayName("50MB ~/.claude.json 도 끝까지 읽어 서버를 찾고 projects 밖의 큰 값을 메모리에 만들지 않는다")
    void largeClaudeJson() throws IOException {
        // given
        LargeClaudeJson.write(homeDir.resolve(".claude.json"), LargeClaudeJson.SIZE);
        ClaudeToolkitProvider provider = new ClaudeToolkitProvider();
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();

        // when
        long allocatedBefore = threads.getCurrentThreadAllocatedBytes();
        ToolkitScan scan = ok(provider.scan(query()));
        long allocated = threads.getCurrentThreadAllocatedBytes() - allocatedBefore;

        // then
        assertThat(names(scan, MCP, ToolkitScope.LOCAL)).containsExactly("db");
        assertThat(names(scan, MCP, ToolkitScope.USER)).containsExactly("tail");
        assertThat(allocated).as("할당 바이트").isLessThan(8L * 1024 * 1024);
    }
}
