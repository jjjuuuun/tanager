package tanager.adapter.toolkit.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static tanager.adapter.toolkit.claude.ClaudeFixture.path;
import static tanager.adapter.toolkit.claude.Scans.item;
import static tanager.adapter.toolkit.claude.Scans.items;
import static tanager.adapter.toolkit.claude.Scans.names;
import static tanager.adapter.toolkit.claude.Scans.ok;
import static tanager.adapter.toolkit.claude.Scans.plain;
import static tanager.adapter.toolkit.claude.Scans.source;

import java.io.IOException;
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

/**
 * 훅 · 권한 — {@code settings.json} · {@code settings.local.json} · {@code ~/.claude/settings.json} · 관리형 {@code
 * managed-settings.json}(#213). 픽스처의 비밀값은 모두 합성이다.
 */
class ClaudeSettingsTest {

    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();

    private static final WorkspacePath PROJECT = path(WSL, "/home/dev/app");
    private static final WorkspacePath HOME = path(WSL, "/home/dev");
    private static final WorkspacePath MANAGED = path(WSL, "/etc/claude-code");

    private static final ToolkitKind HOOK = ToolkitKind.HOOK;
    private static final ToolkitKind PERMISSION = ToolkitKind.PERMISSION;
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
        fixture =
                new ClaudeFixture().copy("settings/home", homeDir, HOME).copy("settings/project", projectDir, PROJECT);
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

    private static List<ToolkitItem> named(ToolkitScan scan, ToolkitKind kind, ToolkitScope scope, String name) {
        return items(scan, kind, scope).stream()
                .filter(i -> i.name().equals(name))
                .toList();
    }

    @Test
    @DisplayName(
            "settings.json 의 PreToolUse · Bash 훅은 HOOK · PROJECT · type=command · command=.claude/hooks/guard-git.sh 다")
    void projectHook() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem hook = item(scan, HOOK, ToolkitScope.PROJECT, "PreToolUse · Bash");
        assertThat(hook.source()).isEqualTo(source(PROJECT, ".claude/settings.json"));
        assertThat(hook.attributes())
                .containsExactly(plain("type", "command"), plain("command", ".claude/hooks/guard-git.sh"));
        assertThat(hook.description()).isEmpty();
        assertThat(hook.shadowed()).isFalse();
    }

    @Test
    @DisplayName("matcher 그룹의 훅 하나가 항목 하나이고 matcher 가 없으면 이름은 이벤트만이다")
    void oneItemPerHandler() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(names(scan, HOOK, ToolkitScope.PROJECT))
                .containsExactly(
                        "PostToolUse · Edit|Write", "PostToolUse · Edit|Write", "PreToolUse · Bash", "Stop", "Stop");
        assertThat(named(scan, HOOK, ToolkitScope.PROJECT, "Stop"))
                .extracting(ToolkitItem::attributes)
                .containsExactly(
                        List.of(
                                plain("type", "command"),
                                plain("command", "API_TOKEN=" + M + " ./notify.sh --webhook-secret " + M),
                                plain("timeout", "10")),
                        List.of(plain("type", "prompt")));
    }

    @Test
    @DisplayName("훅 명령의 Authorization: Bearer 토큰은 가리고 timeout 은 보인다")
    void hookCommandRedacted() {
        // when
        ToolkitScan scan = scan();

        // then
        List<ToolkitItem> post = named(scan, HOOK, ToolkitScope.PROJECT, "PostToolUse · Edit|Write");
        assertThat(post.getFirst().attributes())
                .containsExactly(
                        plain("type", "command"),
                        plain("command", "curl -H \"Authorization: Bearer " + M + "\" https://hooks.example.com/x"),
                        plain("timeout", "30"));
    }

    @Test
    @DisplayName("http 훅의 url 은 스킴 · 호스트 · 포트만이고 headers 키는 Masked 다")
    void httpHook() {
        // when
        ToolkitScan scan = scan();

        // then
        List<ToolkitItem> post = named(scan, HOOK, ToolkitScope.PROJECT, "PostToolUse · Edit|Write");
        assertThat(post.get(1).attributes())
                .containsExactly(
                        plain("type", "http"), plain("url", "https://hooks.example.com"), masked("headers.X-Api-Key"));
    }

    @Test
    @DisplayName("mcp_tool 훅은 server · tool 을 보이고 input 은 읽지 않는다")
    void mcpToolHook() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem hook = item(scan, HOOK, ToolkitScope.USER, "PostToolUse · mcp__memory__.*");
        assertThat(hook.source()).isEqualTo(source(HOME, ".claude/settings.json"));
        assertThat(hook.attributes())
                .containsExactly(plain("type", "mcp_tool"), plain("server", "memory"), plain("tool", "log"));
        assertThat(item(scan, HOOK, ToolkitScope.USER, "Notification").attributes())
                .containsExactly(plain("type", "command"), plain("command", "notify-send Claude"));
    }

    @Test
    @DisplayName("Bearer 토큰이 든 curl 훅 명령의 command 속성에 토큰이 없다")
    void bearerTokenGone() throws IOException {
        // given
        write(projectDir.resolve(".claude/settings.json"), """
                {"hooks": {"PreToolUse": [{"matcher": "Bash", "hooks": [
                  {"type": "command", "command": "curl -H \\"Authorization: Bearer S3CR3T-7\\" https://x"}
                ]}]}}
                """);

        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem hook = item(scan, HOOK, ToolkitScope.PROJECT, "PreToolUse · Bash");
        assertThat(hook.attributes())
                .containsExactly(
                        plain("type", "command"),
                        plain("command", "curl -H \"Authorization: Bearer " + M + "\" https://x"));
        assertThat(hook.toString()).doesNotContain("S3CR3T-7");
    }

    @Test
    @DisplayName("200자 훅 명령은 80 코드 포인트로 자른다")
    void longCommandCut() throws IOException {
        // given
        String command = "echo " + "가".repeat(195);
        write(projectDir.resolve(".claude/settings.json"), """
                {"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "%s"}]}]}}
                """.formatted(command));

        // when
        ToolkitScan scan = scan();

        // then
        Attribute attribute =
                item(scan, HOOK, ToolkitScope.PROJECT, "Stop").attributes().get(1);
        assertThat(attribute.key()).isEqualTo("command");
        String shown = ((AttributeValue.Plain) attribute.value()).text();
        assertThat(shown.codePointCount(0, shown.length())).isEqualTo(SettingsJson.MAX_COMMAND_CODE_POINTS);
        assertThat(SettingsJson.MAX_COMMAND_CODE_POINTS).isEqualTo(80);
        assertThat(shown).startsWith("echo 가가").endsWith("…");
    }

    @Test
    @DisplayName("permissions 의 allow · ask · deny 규칙 하나가 PERMISSION 항목 하나이고 list 가 그 목록이다")
    void permissionRules() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(item(scan, PERMISSION, ToolkitScope.PROJECT, "Bash(git status)")
                        .attributes())
                .containsExactly(plain("list", "allow"));
        assertThat(item(scan, PERMISSION, ToolkitScope.PROJECT, "Bash(git push --force:*)")
                        .attributes())
                .containsExactly(plain("list", "deny"));
        assertThat(item(scan, PERMISSION, ToolkitScope.PROJECT, "Read(./docs/**)")
                        .source())
                .isEqualTo(source(PROJECT, ".claude/settings.json"));
    }

    @Test
    @DisplayName("defaultMode · additionalDirectories 는 permissions 항목 하나의 속성이다")
    void permissionDefaults() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(item(scan, PERMISSION, ToolkitScope.PROJECT, SettingsJson.DEFAULTS_NAME)
                        .attributes())
                .containsExactly(
                        plain("defaultMode", "acceptEdits"), plain("additionalDirectories", "../shared, /srv/data"));
        assertThat(SettingsJson.DEFAULTS_NAME).isEqualTo("permissions");
        assertThat(named(scan, PERMISSION, ToolkitScope.USER, SettingsJson.DEFAULTS_NAME))
                .isEmpty();
    }

    @Test
    @DisplayName("settings.local.json 의 권한 · 훅은 LOCAL 이고 헤더 이름 뒤의 비밀 값은 가린다")
    void local() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem ask = item(scan, PERMISSION, ToolkitScope.LOCAL, "Bash(rm:*)");
        assertThat(ask.attributes()).containsExactly(plain("list", "ask"));
        assertThat(ask.source()).isEqualTo(source(PROJECT, ".claude/settings.local.json"));
        assertThat(names(scan, PERMISSION, ToolkitScope.LOCAL)).containsExactly("Bash(npm test)", "Bash(rm:*)");
        assertThat(item(scan, HOOK, ToolkitScope.LOCAL, "SessionStart · startup")
                        .attributes())
                .containsExactly(
                        plain("type", "command"),
                        plain("command", "curl -H \"X-Api-Key: " + M + "\" https://hooks.example.com/start"));
    }

    @Test
    @DisplayName("같은 규칙이 PROJECT · USER 에 있어도 가리지 않는다 — 권한 · 훅은 합쳐 적용된다")
    void notShadowed() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(item(scan, PERMISSION, ToolkitScope.PROJECT, "Bash(git status)")
                        .shadowed())
                .isFalse();
        assertThat(item(scan, PERMISSION, ToolkitScope.USER, "Bash(git status)").shadowed())
                .isFalse();
        assertThat(scan.items()).filteredOn(ToolkitItem::shadowed).isEmpty();
    }

    @Test
    @DisplayName("env · apiKeyHelper 등 다른 키는 키도 값도 결과에 없다")
    void envIgnored() {
        // when
        ToolkitScan scan = scan();

        // then
        for (ToolkitItem item : scan.items()) {
            assertThat(item.toString())
                    .doesNotContain("API_KEY")
                    .doesNotContain("LOG_LEVEL")
                    .doesNotContain("apiKeyHelper")
                    .doesNotContain("S3CR3T");
        }
    }

    @Test
    @DisplayName("관리형 위치에 managed-settings.json 이 있으면 MANAGED 훅 · 권한이 보이고 drop-in *.json 도 읽는다")
    void managed() {
        // given
        fixture.copy("settings/managed", tmp.resolve("managed"), MANAGED);

        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem hook = item(scan, HOOK, ToolkitScope.MANAGED, "PreToolUse · *");
        assertThat(hook.source()).isEqualTo(new SourceRef(path(WSL, "/etc/claude-code/managed-settings.json")));
        assertThat(hook.attributes())
                .containsExactly(plain("type", "command"), plain("command", "/opt/corp/audit.sh --auth-token " + M));
        assertThat(names(scan, PERMISSION, ToolkitScope.MANAGED))
                .containsExactly("Bash(curl:*)", "Read(./.env)", SettingsJson.DEFAULTS_NAME);
        assertThat(item(scan, PERMISSION, ToolkitScope.MANAGED, "Read(./.env)").source())
                .isEqualTo(new SourceRef(path(WSL, "/etc/claude-code/managed-settings.d/10-security.json")));
        assertThat(item(scan, PERMISSION, ToolkitScope.MANAGED, SettingsJson.DEFAULTS_NAME)
                        .attributes())
                .containsExactly(plain("defaultMode", "default"));
    }

    @Test
    @DisplayName("관리형 항목은 HOOK · PERMISSION 마다 맨 앞이다")
    void managedFirst() {
        // given
        fixture.copy("settings/managed", tmp.resolve("managed"), MANAGED);

        // when
        ToolkitScan scan = scan();

        // then
        for (ToolkitKind kind : List.of(HOOK, PERMISSION)) {
            List<ToolkitItem> ofKind =
                    scan.items().stream().filter(i -> i.kind() == kind).toList();
            assertThat(ofKind.getFirst().scope()).as("%s", kind).isEqualTo(ToolkitScope.MANAGED);
        }
    }

    @Test
    @DisplayName("관리형 위치가 비었으면 MANAGED 훅 · 권한이 없다")
    void noManaged() throws IOException {
        // given
        fixture.mount(MANAGED, Files.createDirectories(tmp.resolve("empty-managed")));

        // when
        ToolkitScan scan = scan();

        // then
        assertThat(items(scan, HOOK, ToolkitScope.MANAGED)).isEmpty();
        assertThat(items(scan, PERMISSION, ToolkitScope.MANAGED)).isEmpty();
        assertThat(scan.warnings()).isEmpty();
    }

    @Test
    @DisplayName("관리형 위치 하나에서 CLAUDE.md · managed-mcp.json · managed-settings.json 을 모두 MANAGED 로 읽는다")
    void managedAllKinds() throws IOException {
        // given
        Path managed = tmp.resolve("managed");
        fixture.copy("settings/managed", managed, MANAGED);
        write(managed.resolve("CLAUDE.md"), "# Synthetic org policy\n");
        write(managed.resolve("managed-mcp.json"), """
                {"mcpServers": {"corp": {"command": "corp-mcp"}}}
                """);

        // when
        ToolkitScan scan = scan();

        // then
        assertThat(names(scan, ToolkitKind.MEMORY, ToolkitScope.MANAGED)).containsExactly("CLAUDE.md");
        assertThat(names(scan, ToolkitKind.MCP_SERVER, ToolkitScope.MANAGED)).containsExactly("corp");
        assertThat(names(scan, HOOK, ToolkitScope.MANAGED)).containsExactly("PreToolUse · *");
    }

    @Test
    @DisplayName("Windows 의 관리형 위치는 C:\\Program Files\\ClaudeCode 다")
    void managedWindows() throws IOException {
        // given
        WorkspacePath project = path(WINDOWS, "C:\\work\\app");
        Path winProject = Files.createDirectories(tmp.resolve("win-project"));
        Path winManaged = tmp.resolve("win-managed");
        write(winManaged.resolve("managed-settings.json"), """
                {"permissions": {"deny": ["Bash(curl:*)"]}}
                """);
        ClaudeFixture win = new ClaudeFixture()
                .mount(project, winProject)
                .mount(path(WINDOWS, "C:\\Program Files\\ClaudeCode"), winManaged);

        // when
        ToolkitScan scan = scan(new ToolkitQuery(project, Optional.empty(), win.hostFunction()));

        // then
        assertThat(item(scan, PERMISSION, ToolkitScope.MANAGED, "Bash(curl:*)").source())
                .isEqualTo(new SourceRef(path(WINDOWS, "C:\\Program Files\\ClaudeCode\\managed-settings.json")));
    }

    @Test
    @DisplayName("깨진 settings.local.json 은 Malformed(줄) 경고이고 PROJECT 훅 · 권한은 그대로다")
    void malformedLocal() {
        // given
        WorkspacePath broken = path(WSL, "/home/dev/broken");
        fixture.copy("settings/broken", tmp.resolve("broken"), broken);

        // when
        ToolkitScan scan = scan(new ToolkitQuery(broken, Optional.empty(), fixture.hostFunction()));

        // then
        assertThat(scan.warnings())
                .containsExactly(
                        new ToolkitWarning.Malformed(source(broken, ".claude/settings.local.json"), OptionalInt.of(3)));
        assertThat(names(scan, PERMISSION, ToolkitScope.PROJECT)).containsExactly("Bash(make)");
        assertThat(names(scan, HOOK, ToolkitScope.PROJECT)).containsExactly("PreToolUse · Bash");
        assertThat(items(scan, PERMISSION, ToolkitScope.LOCAL)).isEmpty();
    }

    @Test
    @DisplayName("hooks · permissions 의 모양이 예상과 다르면 그 자리만 건너뛴다")
    void unexpectedShapes() throws IOException {
        // given
        write(projectDir.resolve(".claude/settings.json"), """
                {"hooks": {"A": "x", "B": [1, {"matcher": 3, "hooks": "y"}, {"hooks": [2, {"command": "ok", "timeout": "soon"}]}]},
                 "permissions": {"allow": "Bash", "deny": [1, "Bash(rm:*)", {"x": 1}], "defaultMode": 2, "additionalDirectories": "x"}}
                """);
        write(projectDir.resolve(".claude/settings.local.json"), """
                {"hooks": [1], "permissions": [2]}
                """);

        // when
        ToolkitScan scan = scan();

        // then
        assertThat(names(scan, HOOK, ToolkitScope.PROJECT)).containsExactly("B");
        assertThat(item(scan, HOOK, ToolkitScope.PROJECT, "B").attributes()).containsExactly(plain("command", "ok"));
        assertThat(names(scan, PERMISSION, ToolkitScope.PROJECT)).containsExactly("Bash(rm:*)");
        assertThat(items(scan, HOOK, ToolkitScope.LOCAL)).isEmpty();
        assertThat(items(scan, PERMISSION, ToolkitScope.LOCAL)).isEmpty();
        assertThat(scan.warnings()).isEmpty();
    }

    @Test
    @DisplayName(
            "지문 입력에 세 settings 파일 · managed-settings.json · managed-settings.d 가 들어 있고 settings.json 을 고치면 stamp 가 달라진다")
    void stamp() throws IOException {
        // given
        Path managedDir = tmp.resolve("missing-managed");
        fixture.mount(MANAGED, managedDir);
        ClaudeToolkitProvider provider = new ClaudeToolkitProvider();
        ScanStamp before = provider.stamp(query()).orElseThrow();

        // when
        Path settings = projectDir.resolve(".claude/settings.json");
        Files.setLastModifiedTime(settings, FileTime.from(Instant.parse("2030-01-01T00:00:00Z")));
        ScanStamp after = provider.stamp(query()).orElseThrow();

        // then
        assertThat(before.inputs())
                .containsKeys(
                        settings,
                        projectDir.resolve(".claude/settings.local.json"),
                        homeDir.resolve(".claude/settings.json"),
                        managedDir.resolve("managed-settings.json"),
                        managedDir.resolve("managed-settings.d"))
                .isNotEqualTo(after.inputs());
        assertThat(ok(provider.scan(query())).stamp()).contains(after);
    }
}
