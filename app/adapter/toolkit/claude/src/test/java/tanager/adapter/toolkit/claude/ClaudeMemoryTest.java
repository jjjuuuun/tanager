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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.out.ScanStamp;
import tanager.application.port.out.ToolkitQuery;
import tanager.application.port.out.ToolkitScan;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.toolkit.AttributeValue;
import tanager.domain.toolkit.SourceRef;
import tanager.domain.toolkit.ToolkitItem;
import tanager.domain.toolkit.ToolkitKind;
import tanager.domain.toolkit.ToolkitScope;
import tanager.domain.toolkit.ToolkitWarning;

/** 메모리(CLAUDE.md · CLAUDE.local.md · 상위 디렉터리 · 관리형)와 rules(#211). */
class ClaudeMemoryTest {

    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();

    private static final WorkspacePath PROJECT = path(WSL, "/home/dev/작업 공간/내 프로젝트");
    private static final WorkspacePath HOME = path(WSL, "/home/dev");

    @TempDir
    Path tmp;

    private Path projectDir;
    private Path homeDir;
    private ClaudeFixture fixture;

    @BeforeEach
    void setUp() {
        projectDir = tmp.resolve("작업 공간").resolve("내 프로젝트");
        homeDir = tmp.resolve("home");
        fixture = new ClaudeFixture().copy("home", homeDir, HOME).copy("project", projectDir, PROJECT);
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

    private static Optional<String> attribute(ToolkitItem item, String key) {
        return item.attributes().stream()
                .filter(a -> a.key().equals(key))
                .map(a -> ((AttributeValue.Plain) a.value()).text())
                .findFirst();
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** 루트 {@code /} 를 임시 폴더에 붙인 WSL 환경(상위 디렉터리 시험용). */
    private ClaudeFixture rootMounted(Path root) {
        return new ClaudeFixture().mount(path(WSL, "/"), root);
    }

    @Test
    @DisplayName("프로젝트 CLAUDE.md(12줄, 첫 제목 # CLAUDE.md)는 MEMORY · PROJECT · lines=12 · heading=CLAUDE.md 다")
    void projectClaudeMd() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem memory = item(scan, ToolkitKind.MEMORY, ToolkitScope.PROJECT, "CLAUDE.md");
        assertThat(memory.source()).isEqualTo(source(PROJECT, "CLAUDE.md"));
        assertThat(memory.attributes())
                .containsExactly(plain("lines", "12"), plain("heading", "CLAUDE.md"), plain("imports", "2"));
        assertThat(memory.shadowed()).isFalse();
    }

    @Test
    @DisplayName("CLAUDE.md 와 .claude/CLAUDE.md 가 둘 다 있으면 둘 다 PROJECT 메모리로 보인다")
    void bothProjectMemories() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(names(scan, ToolkitKind.MEMORY, ToolkitScope.PROJECT))
                .containsExactly(".claude/CLAUDE.md", "CLAUDE.md");
        ToolkitItem dotClaude = item(scan, ToolkitKind.MEMORY, ToolkitScope.PROJECT, ".claude/CLAUDE.md");
        assertThat(dotClaude.source()).isEqualTo(source(PROJECT, ".claude/CLAUDE.md"));
        assertThat(dotClaude.attributes())
                .containsExactly(plain("lines", "3"), plain("heading", "Synthetic dot-claude memory"));
    }

    @Test
    @DisplayName("CLAUDE.md 없이 .claude/CLAUDE.md 만 있으면 그것이 PROJECT 메모리다")
    void onlyDotClaudeMemory() throws IOException {
        // given
        WorkspacePath project = path(WSL, "/srv/only");
        Path dir = Files.createDirectories(tmp.resolve("only"));
        write(dir.resolve(".claude").resolve("CLAUDE.md"), "# Only\n");
        ClaudeFixture only = new ClaudeFixture().mount(project, dir);

        // when
        ToolkitScan scan = scan(new ToolkitQuery(project, Optional.empty(), only.hostFunction()));

        // then
        assertThat(items(scan, ToolkitKind.MEMORY, ToolkitScope.PROJECT))
                .extracting(ToolkitItem::source)
                .containsExactly(source(project, ".claude/CLAUDE.md"));
    }

    @Test
    @DisplayName("CLAUDE.local.md 는 LOCAL 메모리다")
    void localMemory() throws IOException {
        // given
        write(projectDir.resolve("CLAUDE.local.md"), "# Local notes\nline two\n");

        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem local = item(scan, ToolkitKind.MEMORY, ToolkitScope.LOCAL, "CLAUDE.local.md");
        assertThat(local.source()).isEqualTo(source(PROJECT, "CLAUDE.local.md"));
        assertThat(local.attributes()).containsExactly(plain("lines", "2"), plain("heading", "Local notes"));
    }

    @Test
    @DisplayName("/a/b/c 를 조회하고 /a/CLAUDE.md · /a/b/CLAUDE.md 가 있으면 PARENT 두 개가 가까운 순이고 설명은 그 디렉터리다")
    void parentMemoriesNearestFirst() throws IOException {
        // given
        Path root = tmp.resolve("root");
        Files.createDirectories(root.resolve("a").resolve("b").resolve("c"));
        write(root.resolve("a").resolve("CLAUDE.md"), "# A\n");
        write(root.resolve("a").resolve("b").resolve("CLAUDE.md"), "# B\n");
        WorkspacePath project = path(WSL, "/a/b/c");

        // when
        ToolkitScan scan = scan(
                new ToolkitQuery(project, Optional.empty(), rootMounted(root).hostFunction()));

        // then
        List<ToolkitItem> parents = items(scan, ToolkitKind.MEMORY, ToolkitScope.PARENT);
        assertThat(parents)
                .extracting(ToolkitItem::source)
                .containsExactly(new SourceRef(path(WSL, "/a/b/CLAUDE.md")), new SourceRef(path(WSL, "/a/CLAUDE.md")));
        assertThat(parents).extracting(ToolkitItem::name).containsOnly("CLAUDE.md");
        assertThat(parents)
                .extracting(ToolkitItem::description)
                .containsExactly(Optional.of("/a/b"), Optional.of("/a"));
        assertThat(parents).allMatch(i -> !i.shadowed());
        assertThat(items(scan, ToolkitKind.MEMORY, ToolkitScope.PROJECT)).isEmpty();
    }

    @Test
    @DisplayName("상위 디렉터리의 CLAUDE.local.md 도 PARENT 로 보인다(Claude Code 문서: 위로 올라가며 둘 다 읽는다)")
    void parentLocalMemory() throws IOException {
        // given
        Path root = tmp.resolve("root");
        Files.createDirectories(root.resolve("a").resolve("b"));
        write(root.resolve("a").resolve("CLAUDE.local.md"), "# A local\n");
        WorkspacePath project = path(WSL, "/a/b");

        // when
        ToolkitScan scan = scan(
                new ToolkitQuery(project, Optional.empty(), rootMounted(root).hostFunction()));

        // then
        ToolkitItem local = item(scan, ToolkitKind.MEMORY, ToolkitScope.PARENT, "CLAUDE.local.md");
        assertThat(local.source()).isEqualTo(new SourceRef(path(WSL, "/a/CLAUDE.local.md")));
    }

    @Test
    @DisplayName("홈 디렉터리 자신의 CLAUDE.md 는 PARENT 이고 ~/.claude/CLAUDE.md 는 USER 다")
    void homeClaudeMdIsParent() throws IOException {
        // given
        write(homeDir.resolve("CLAUDE.md"), "# Home root memory\n");

        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem parent = item(scan, ToolkitKind.MEMORY, ToolkitScope.PARENT, "CLAUDE.md");
        assertThat(parent.source()).isEqualTo(source(HOME, "CLAUDE.md"));
        assertThat(parent.description()).contains("/home/dev");
        ToolkitItem user = item(scan, ToolkitKind.MEMORY, ToolkitScope.USER, "CLAUDE.md");
        assertThat(user.source()).isEqualTo(source(HOME, ".claude/CLAUDE.md"));
        assertThat(attribute(user, "heading")).contains("Synthetic user memory");
    }

    @Test
    @DisplayName("33단계 깊이의 경로는 32단계까지만 올라간다")
    void parentWalkStopsAt32() throws IOException {
        // given
        Path root = tmp.resolve("root");
        StringBuilder raw = new StringBuilder();
        Path deepest = root;
        for (int i = 1; i <= 33; i++) {
            raw.append("/d").append(i);
            deepest = deepest.resolve("d" + i);
        }
        Files.createDirectories(deepest);
        write(root.resolve("d1").resolve("CLAUDE.md"), "# Depth one\n");
        write(root.resolve("CLAUDE.md"), "# Root\n");
        WorkspacePath project = path(WSL, raw.toString());
        ToolkitQuery query =
                new ToolkitQuery(project, Optional.empty(), rootMounted(root).hostFunction());

        // when
        ToolkitScan scan = scan(query);

        // then
        assertThat(items(scan, ToolkitKind.MEMORY, ToolkitScope.PARENT))
                .extracting(ToolkitItem::source)
                .containsExactly(new SourceRef(path(WSL, "/d1/CLAUDE.md")));
        assertThat(scan.stamp().orElseThrow().inputs()).doesNotContainKey(root.resolve("CLAUDE.md"));
    }

    @Test
    @DisplayName("~/.claude/CLAUDE.md 는 USER 메모리다")
    void userMemory() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem user = item(scan, ToolkitKind.MEMORY, ToolkitScope.USER, "CLAUDE.md");
        assertThat(user.attributes()).containsExactly(plain("lines", "3"), plain("heading", "Synthetic user memory"));
    }

    @Test
    @DisplayName("관리형 위치(WSL /etc/claude-code)의 CLAUDE.md 는 MANAGED 메모리다")
    void managedMemoryWsl() throws IOException {
        // given
        Path managed = Files.createDirectories(tmp.resolve("managed"));
        write(managed.resolve("CLAUDE.md"), "# Synthetic org policy\n");
        fixture.mount(path(WSL, "/etc/claude-code"), managed);

        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem item = item(scan, ToolkitKind.MEMORY, ToolkitScope.MANAGED, "CLAUDE.md");
        assertThat(item.source()).isEqualTo(new SourceRef(path(WSL, "/etc/claude-code/CLAUDE.md")));
    }

    @Test
    @DisplayName("관리형 위치(Windows C:\\Program Files\\ClaudeCode)의 CLAUDE.md 는 MANAGED 메모리다")
    void managedMemoryWindows() throws IOException {
        // given
        WorkspacePath project = path(WINDOWS, "C:\\Users\\dev\\proj");
        Path projectHost = Files.createDirectories(tmp.resolve("win-proj"));
        Path managed = Files.createDirectories(tmp.resolve("win-managed"));
        write(managed.resolve("CLAUDE.md"), "# Synthetic org policy\n");
        ClaudeFixture windows = new ClaudeFixture()
                .mount(project, projectHost)
                .mount(path(WINDOWS, "C:\\Program Files\\ClaudeCode"), managed);

        // when
        ToolkitScan scan = scan(new ToolkitQuery(project, Optional.empty(), windows.hostFunction()));

        // then
        assertThat(item(scan, ToolkitKind.MEMORY, ToolkitScope.MANAGED, "CLAUDE.md")
                        .source())
                .isEqualTo(new SourceRef(path(WINDOWS, "C:\\Program Files\\ClaudeCode\\CLAUDE.md")));
    }

    @Test
    @DisplayName("관리형 위치에 CLAUDE.md 가 없으면 MANAGED 메모리가 없다")
    void noManagedMemory() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(items(scan, ToolkitKind.MEMORY, ToolkitScope.MANAGED)).isEmpty();
    }

    @Test
    @DisplayName(
            ".claude/rules/java/style.md 에 paths: [\"**/*.java\"] 가 있으면 RULE java/style.md · load=paths · paths=**/*.java 다")
    void pathScopedRule() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem rule = item(scan, ToolkitKind.RULE, ToolkitScope.PROJECT, "java/style.md");
        assertThat(rule.source()).isEqualTo(source(PROJECT, ".claude/rules/java/style.md"));
        assertThat(rule.attributes())
                .containsExactly(
                        plain("load", "paths"),
                        plain("paths", "**/*.java"),
                        plain("lines", "7"),
                        plain("heading", "Synthetic Java style"));
    }

    @Test
    @DisplayName("프런트매터가 없는 규칙은 load=always 다")
    void ruleWithoutFrontmatterAlwaysLoads() {
        // when
        ToolkitItem rule = item(scan(), ToolkitKind.RULE, ToolkitScope.PROJECT, "git.md");

        // then
        assertThat(rule.attributes())
                .containsExactly(plain("load", "always"), plain("lines", "3"), plain("heading", "Synthetic git rule"));
    }

    @Test
    @DisplayName("paths 가 쉼표로 이은 글자여도 그대로 paths 속성이다")
    void commaSeparatedPaths() throws IOException {
        // given
        write(
                projectDir.resolve(".claude").resolve("rules").resolve("web.md"),
                "---\npaths: \"src/**/*.ts, lib/**/*.ts\"\n---\n# Web\n");

        // when
        ToolkitItem rule = item(scan(), ToolkitKind.RULE, ToolkitScope.PROJECT, "web.md");

        // then
        assertThat(attribute(rule, "load")).contains("paths");
        assertThat(attribute(rule, "paths")).contains("src/**/*.ts, lib/**/*.ts");
    }

    @Test
    @DisplayName("~/.claude/rules/git.md 는 RULE · USER 이고, 같은 이름의 PROJECT 규칙과 서로 가리지 않는다")
    void userRule() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem user = item(scan, ToolkitKind.RULE, ToolkitScope.USER, "git.md");
        assertThat(user.source()).isEqualTo(source(HOME, ".claude/rules/git.md"));
        assertThat(user.shadowed()).isFalse();
        assertThat(item(scan, ToolkitKind.RULE, ToolkitScope.PROJECT, "git.md").shadowed())
                .isFalse();
    }

    @Test
    @DisplayName("깨진 rules 프런트매터는 Malformed 경고이고, Claude Code 처럼 paths 없는 규칙(load=always)으로 보인다")
    void malformedRuleFrontmatter() throws IOException {
        // given
        write(
                projectDir.resolve(".claude").resolve("rules").resolve("broken.md"),
                "---\npaths: [\"unterminated\n---\n# Broken rule\n");

        // when
        ToolkitScan scan = scan();

        // then
        assertThat(scan.warnings())
                .anySatisfy(w -> assertThat(w)
                        .isInstanceOfSatisfying(
                                ToolkitWarning.Malformed.class,
                                m -> assertThat(m.source()).isEqualTo(source(PROJECT, ".claude/rules/broken.md"))));
        ToolkitItem rule = item(scan, ToolkitKind.RULE, ToolkitScope.PROJECT, "broken.md");
        assertThat(attribute(rule, "load")).contains("always");
        assertThat(attribute(rule, "paths")).isEmpty();
        assertThat(attribute(rule, "heading")).contains("Broken rule");
    }

    @Test
    @DisplayName("@docs/x.md 줄 2개는 imports=2 이고 docs/x.md 는 열지 않는다")
    void importsAreCountedNotFollowed() {
        // given
        List<Path> opened = new CopyOnWriteArrayList<>();
        FileOpener recording = file -> {
            opened.add(file);
            return Files.newInputStream(file);
        };

        // when
        ToolkitScan scan = ok(new ClaudeToolkitProvider(recording).scan(query()));

        // then
        ToolkitItem memory = item(scan, ToolkitKind.MEMORY, ToolkitScope.PROJECT, "CLAUDE.md");
        assertThat(attribute(memory, "imports")).contains("2");
        assertThat(opened).contains(projectDir.resolve("CLAUDE.md"));
        assertThat(opened).noneMatch(p -> p.startsWith(projectDir.resolve("docs")));
    }

    @Test
    @DisplayName("1MB 를 넘는 CLAUDE.md 는 1MB 만 읽고 lines 가 >N 이다")
    void hugeMemoryReadsOnly1Mb() throws IOException {
        // given
        Path huge = projectDir.resolve("CLAUDE.local.md");
        StringBuilder body = new StringBuilder("# Huge\n");
        while (body.length() < 2 * 1024 * 1024) {
            body.append("padding padding padding padding\n");
        }
        write(huge, body.toString());
        AtomicLong hugeBytes = new AtomicLong();
        FileOpener counting = file -> {
            InputStream in = Files.newInputStream(file);
            return file.equals(huge) ? new CountingStream(in, hugeBytes) : in;
        };

        // when
        ToolkitScan scan = ok(new ClaudeToolkitProvider(counting).scan(query()));

        // then
        assertThat(hugeBytes.get()).isLessThanOrEqualTo(1024 * 1024 + 1).isPositive();
        ToolkitItem local = item(scan, ToolkitKind.MEMORY, ToolkitScope.LOCAL, "CLAUDE.local.md");
        assertThat(attribute(local, "lines"))
                .hasValueSatisfying(lines -> assertThat(lines).startsWith(">"));
        assertThat(attribute(local, "heading")).contains("Huge");
    }

    @Test
    @DisplayName("CLAUDE.md 의 본문을 바꾸면 stamp 가 달라진다")
    void stampChangesWhenMemoryEdited() throws IOException {
        // given
        ClaudeToolkitProvider provider = new ClaudeToolkitProvider();
        ScanStamp scanned = ok(provider.scan(query())).stamp().orElseThrow();
        Path memory = projectDir.resolve("CLAUDE.md");
        FileTime before = Files.getLastModifiedTime(memory);
        write(memory, "# Edited\n");
        Files.setLastModifiedTime(memory, FileTime.from(before.toInstant().plusSeconds(60)));

        // when
        Optional<ScanStamp> stamp = provider.stamp(query());

        // then
        assertThat(stamp).isPresent().get().isNotEqualTo(scanned);
    }

    @Test
    @DisplayName("없던 CLAUDE.local.md · 상위 CLAUDE.md · rules 파일이 생기면 stamp 가 달라진다")
    void stampChangesWhenMemoryAppears() throws IOException {
        // given
        ClaudeToolkitProvider provider = new ClaudeToolkitProvider();
        ScanStamp scanned = ok(provider.scan(query())).stamp().orElseThrow();
        assertThat(scanned.inputs())
                .containsKey(projectDir.resolve("CLAUDE.local.md"))
                .containsKey(homeDir.resolve("CLAUDE.md"))
                .containsKey(projectDir.resolve(".claude").resolve("rules"));

        // when
        write(projectDir.resolve("CLAUDE.local.md"), "# New\n");
        Optional<ScanStamp> stamp = provider.stamp(query());

        // then
        assertThat(stamp).isPresent().get().isNotEqualTo(scanned);
    }

    @Test
    @DisplayName("메모리 · 규칙이 있어도 아무것도 바꾸지 않으면 stamp 가 scan 의 지문과 같다")
    void stampUnchanged() {
        // given
        ClaudeToolkitProvider provider = new ClaudeToolkitProvider();
        ScanStamp scanned = ok(provider.scan(query())).stamp().orElseThrow();

        // when
        Optional<ScanStamp> stamp = provider.stamp(query());

        // then
        assertThat(stamp).contains(scanned);
        assertThat(scanned.inputs()).containsKey(projectDir.resolve("CLAUDE.md"));
    }

    @Test
    @DisplayName("Windows 프로젝트의 상위 디렉터리는 드라이브 루트까지 올라간다")
    void windowsParents() throws IOException {
        // given
        WorkspacePath project = path(WINDOWS, "C:\\Users\\dev\\proj");
        Path drive = Files.createDirectories(tmp.resolve("c-drive"));
        Files.createDirectories(drive.resolve("Users").resolve("dev").resolve("proj"));
        write(drive.resolve("CLAUDE.md"), "# Drive root\n");
        write(drive.resolve("Users").resolve("CLAUDE.md"), "# Users\n");
        ClaudeFixture windows = new ClaudeFixture().mount(path(WINDOWS, "C:\\"), drive);

        // when
        ToolkitScan scan = scan(new ToolkitQuery(project, Optional.empty(), windows.hostFunction()));

        // then
        assertThat(items(scan, ToolkitKind.MEMORY, ToolkitScope.PARENT))
                .extracting(ToolkitItem::source)
                .containsExactly(
                        new SourceRef(path(WINDOWS, "C:\\Users\\CLAUDE.md")),
                        new SourceRef(path(WINDOWS, "C:\\CLAUDE.md")));
        assertThat(scan.warnings()).isEmpty();
    }

    @Test
    @DisplayName("빈 CLAUDE.md 는 lines=0 이고 heading 이 없다")
    void emptyMemory() throws IOException {
        // given
        write(projectDir.resolve("CLAUDE.local.md"), "");

        // when
        ToolkitItem local = item(scan(), ToolkitKind.MEMORY, ToolkitScope.LOCAL, "CLAUDE.local.md");

        // then
        assertThat(local.attributes()).containsExactly(plain("lines", "0"));
    }
}
