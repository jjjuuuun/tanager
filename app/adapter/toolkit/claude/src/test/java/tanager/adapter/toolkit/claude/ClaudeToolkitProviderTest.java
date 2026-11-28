package tanager.adapter.toolkit.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static tanager.adapter.toolkit.claude.ClaudeFixture.path;
import static tanager.adapter.toolkit.claude.Scans.item;
import static tanager.adapter.toolkit.claude.Scans.names;
import static tanager.adapter.toolkit.claude.Scans.ok;
import static tanager.adapter.toolkit.claude.Scans.plain;
import static tanager.adapter.toolkit.claude.Scans.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.out.ScanStamp;
import tanager.application.port.out.ToolkitError;
import tanager.application.port.out.ToolkitProvider;
import tanager.application.port.out.ToolkitQuery;
import tanager.application.port.out.ToolkitScan;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.ProviderId;
import tanager.domain.toolkit.SourceRef;
import tanager.domain.toolkit.ToolkitItem;
import tanager.domain.toolkit.ToolkitKind;
import tanager.domain.toolkit.ToolkitScope;
import tanager.domain.toolkit.ToolkitWarning;

class ClaudeToolkitProviderTest {

    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();
    private static final ProviderId CLAUDE = new ProviderId("claude-code");

    // 한글 · 공백이 든 프로젝트 경로(환경 쪽과 앱이 읽는 쪽 모두)
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

    @Test
    @DisplayName("공급자 id 는 claude-code 이고 WSL · Windows 를 모두 지원한다")
    void idAndSupport() {
        // given
        ClaudeToolkitProvider provider = new ClaudeToolkitProvider();

        // when / then
        assertThat(provider.id()).isEqualTo(CLAUDE);
        assertThat(provider.supports(WSL)).isTrue();
        assertThat(provider.supports(WINDOWS)).isTrue();
    }

    @Test
    @DisplayName("ServiceLoader 가 ToolkitProvider 로 이 공급자를 찾는다")
    void registeredAsService() {
        // when
        List<ToolkitProvider> providers = ServiceLoader.load(ToolkitProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();

        // then
        assertThat(providers).hasAtLeastOneElementOfType(ClaudeToolkitProvider.class);
    }

    @Test
    @DisplayName("프로젝트 스킬 tdd 는 SKILL · PROJECT · 이름 · 설명 · 원본 경로가 그 SKILL.md 다")
    void readsProjectSkill() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem tdd = item(scan, ToolkitKind.SKILL, ToolkitScope.PROJECT, "tdd");
        assertThat(tdd.provider()).isEqualTo(CLAUDE);
        assertThat(tdd.description()).contains("Synthetic test-first workflow for the fixture project");
        assertThat(tdd.source()).isEqualTo(source(PROJECT, ".claude/skills/tdd/SKILL.md"));
        assertThat(tdd.shadowed()).isFalse();
    }

    @Test
    @DisplayName("프런트매터에 name 이 없는 스킬은 디렉터리 이름 commit 이다")
    void skillNameFallsBackToDirectory() {
        // when
        ToolkitItem commit = item(scan(), ToolkitKind.SKILL, ToolkitScope.PROJECT, "commit");

        // then
        assertThat(commit.description()).contains("합성 커밋 스킬 — 이름은 디렉터리 이름이다");
    }

    @Test
    @DisplayName("커맨드 이름은 commands/ 아래 경로를 : 로 이은 것이고 argument-hint 를 속성으로 싣는다")
    void commandNamesUseColonForFolders() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(names(scan, ToolkitKind.COMMAND, ToolkitScope.PROJECT))
                .containsExactly("git:push", "review", "배포 도구:올리기");
        ToolkitItem push = item(scan, ToolkitKind.COMMAND, ToolkitScope.PROJECT, "git:push");
        assertThat(push.attributes()).containsExactly(plain("argument-hint", "[branch]"));
        assertThat(push.source()).isEqualTo(source(PROJECT, ".claude/commands/git/push.md"));
        assertThat(item(scan, ToolkitKind.COMMAND, ToolkitScope.PROJECT, "review")
                        .description())
                .isEmpty();
    }

    @Test
    @DisplayName("서브에이전트의 model · tools 가 속성 두 개이고, tools 가 YAML 목록이면 쉼표로 잇는다")
    void subagentAttributes() {
        // when
        ToolkitScan scan = scan();

        // then
        ToolkitItem reviewer = item(scan, ToolkitKind.SUBAGENT, ToolkitScope.PROJECT, "reviewer");
        assertThat(reviewer.attributes()).containsExactly(plain("model", "opus"), plain("tools", "Read, Grep"));
        ToolkitItem planner = item(scan, ToolkitKind.SUBAGENT, ToolkitScope.PROJECT, "planner");
        assertThat(planner.attributes()).containsExactly(plain("tools", "Read, Glob"));
    }

    @Test
    @DisplayName("서브에이전트는 agents/ 하위 폴더까지 읽고 이름은 name 이다(없으면 파일 이름)")
    void subagentsAreReadRecursively() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(names(scan, ToolkitKind.SUBAGENT, ToolkitScope.PROJECT))
                .containsExactly("planner", "reviewer", "security-auditor");
        assertThat(item(scan, ToolkitKind.SUBAGENT, ToolkitScope.PROJECT, "security-auditor")
                        .source())
                .isEqualTo(source(PROJECT, ".claude/agents/review/security.md"));
    }

    @Test
    @DisplayName("같은 이름 서브에이전트가 PROJECT · USER 에 있으면 USER 쪽이 가려진다")
    void projectSubagentShadowsUser() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(item(scan, ToolkitKind.SUBAGENT, ToolkitScope.PROJECT, "reviewer")
                        .shadowed())
                .isFalse();
        ToolkitItem user = item(scan, ToolkitKind.SUBAGENT, ToolkitScope.USER, "reviewer");
        assertThat(user.shadowed()).isTrue();
        assertThat(user.source()).isEqualTo(source(HOME, ".claude/agents/reviewer.md"));
    }

    @Test
    @DisplayName("같은 이름 스킬이 PROJECT · USER 에 있으면 PROJECT 쪽이 가려진다(Claude Code 문서: personal over project)")
    void userSkillShadowsProject() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(item(scan, ToolkitKind.SKILL, ToolkitScope.USER, "deploy").shadowed())
                .isFalse();
        assertThat(item(scan, ToolkitKind.SKILL, ToolkitScope.PROJECT, "deploy").shadowed())
                .isTrue();
    }

    @Test
    @DisplayName("사용자 범위의 스킬 · 커맨드도 읽는다")
    void readsUserScope() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(names(scan, ToolkitKind.SKILL, ToolkitScope.USER)).containsExactly("deploy", "notes");
        assertThat(names(scan, ToolkitKind.COMMAND, ToolkitScope.USER)).containsExactly("standup");
    }

    @Test
    @DisplayName("프런트매터 YAML 이 깨진 SKILL.md 는 항목 대신 Malformed(그 파일, 줄)이고 다른 스킬은 보인다")
    void malformedFrontmatterBecomesWarning() {
        // when
        ToolkitScan scan = scan();

        // then
        assertThat(scan.warnings())
                .containsExactly(new ToolkitWarning.Malformed(
                        source(PROJECT, ".claude/skills/broken/SKILL.md"), OptionalInt.of(3)));
        assertThat(names(scan, ToolkitKind.SKILL, ToolkitScope.PROJECT)).containsExactly("commit", "deploy", "tdd");
    }

    @Test
    @DisplayName("홈이 empty 면 사용자 범위 없이 프로젝트 항목만 준다")
    void noHomeMeansProjectOnly() {
        // given
        ToolkitQuery query = new ToolkitQuery(PROJECT, Optional.empty(), fixture.hostFunction());

        // when
        ToolkitScan scan = ok(new ClaudeToolkitProvider().scan(query));

        // then
        assertThat(scan.items()).isNotEmpty().allMatch(i -> i.scope() == ToolkitScope.PROJECT);
        assertThat(item(scan, ToolkitKind.SKILL, ToolkitScope.PROJECT, "deploy").shadowed())
                .isFalse();
    }

    @Test
    @DisplayName(".claude/ 가 없는 폴더는 빈 결과이고 경고가 없다")
    void folderWithoutClaudeDirIsEmpty() throws IOException {
        // given
        WorkspacePath plain = path(WSL, "/srv/plain");
        Path plainDir = Files.createDirectories(tmp.resolve("plain"));
        ClaudeFixture bare = new ClaudeFixture().mount(plain, plainDir);
        ToolkitQuery query = new ToolkitQuery(plain, Optional.empty(), bare.hostFunction());

        // when
        ToolkitScan scan = ok(new ClaudeToolkitProvider().scan(query));

        // then
        assertThat(scan.items()).isEmpty();
        assertThat(scan.warnings()).isEmpty();
        assertThat(scan.stamp()).isPresent();
    }

    @Test
    @DisplayName("앱이 프로젝트 경로를 열 수 없으면 Unreachable 오류다")
    void unreachableProject() {
        // given
        ToolkitQuery query = new ToolkitQuery(PROJECT, Optional.of(HOME), _ -> Optional.empty());

        // when
        Result<ToolkitScan, ToolkitError> result = new ClaudeToolkitProvider().scan(query);

        // then
        assertThat(result.error()).contains(new ToolkitError.Unreachable());
    }

    @Test
    @DisplayName("Windows 환경이면 원본 경로도 Windows 경로다")
    void windowsSourcePaths() {
        // given
        WorkspacePath project = path(WINDOWS, "C:\\Users\\dev\\내 프로젝트");
        ClaudeFixture windows = new ClaudeFixture().copy("project", tmp.resolve("win"), project);
        ToolkitQuery query = new ToolkitQuery(project, Optional.empty(), windows.hostFunction());

        // when
        ToolkitScan scan = ok(new ClaudeToolkitProvider().scan(query));

        // then
        assertThat(item(scan, ToolkitKind.SKILL, ToolkitScope.PROJECT, "tdd").source())
                .isEqualTo(new SourceRef(path(WINDOWS, "C:\\Users\\dev\\내 프로젝트\\.claude\\skills\\tdd\\SKILL.md")));
    }

    @Test
    @DisplayName("스캔 뒤 아무것도 바꾸지 않으면 stamp 가 scan 의 지문과 같다")
    void stampUnchanged() {
        // given
        ClaudeToolkitProvider provider = new ClaudeToolkitProvider();
        ScanStamp scanned = ok(provider.scan(query())).stamp().orElseThrow();

        // when
        Optional<ScanStamp> stamp = provider.stamp(query());

        // then
        assertThat(stamp).contains(scanned);
        assertThat(scanned.inputs()).containsKey(projectDir.resolve(".claude").resolve("skills"));
    }

    @Test
    @DisplayName("스킬 디렉터리를 새로 만들면 stamp 가 달라진다")
    void stampChangesWhenSkillAdded() throws IOException {
        // given
        ClaudeToolkitProvider provider = new ClaudeToolkitProvider();
        ScanStamp scanned = ok(provider.scan(query())).stamp().orElseThrow();
        Path skill = Files.createDirectories(
                projectDir.resolve(".claude").resolve("skills").resolve("fresh"));
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: fresh\n---\n", StandardCharsets.UTF_8);

        // when
        Optional<ScanStamp> stamp = provider.stamp(query());

        // then
        assertThat(stamp).isPresent().get().isNotEqualTo(scanned);
    }

    @Test
    @DisplayName("없던 .claude/agents 가 생기면 stamp 가 달라진다")
    void stampChangesWhenMissingDirAppears() throws IOException {
        // given
        WorkspacePath plain = path(WSL, "/srv/plain");
        Path plainDir = Files.createDirectories(tmp.resolve("plain"));
        ClaudeFixture bare = new ClaudeFixture().mount(plain, plainDir);
        ToolkitQuery query = new ToolkitQuery(plain, Optional.empty(), bare.hostFunction());
        ClaudeToolkitProvider provider = new ClaudeToolkitProvider();
        ScanStamp scanned = ok(provider.scan(query)).stamp().orElseThrow();
        Files.createDirectories(plainDir.resolve(".claude").resolve("agents"));

        // when
        Optional<ScanStamp> stamp = provider.stamp(query);

        // then
        assertThat(stamp).isPresent().get().isNotEqualTo(scanned);
    }

    @Test
    @DisplayName("서로를 가리키는 심볼릭 링크가 있어도 scan 이 끝난다")
    void symlinkLoopTerminates() throws IOException {
        // given
        Path commands = projectDir.resolve(".claude").resolve("commands");
        Path a = commands.resolve("a");
        Path b = commands.resolve("b");
        assumeTrue(symlink(a, b) && symlink(b, a), "심볼릭 링크를 만들 수 없는 환경");
        Path up = commands.resolve("git").resolve("up");
        assumeTrue(symlink(up, commands), "심볼릭 링크를 만들 수 없는 환경");

        // when
        ToolkitScan scan = scan();

        // then
        assertThat(names(scan, ToolkitKind.COMMAND, ToolkitScope.PROJECT))
                .containsExactly("git:push", "review", "배포 도구:올리기");
    }

    @Test
    @DisplayName("심볼릭 링크 스킬 폴더를 따라가되 같은 실제 폴더는 한 번만 읽는다")
    void symlinkedSkillReadOnce() throws IOException {
        // given
        Path skills = projectDir.resolve(".claude").resolve("skills");
        Path target = Files.createDirectories(tmp.resolve("shared-skill"));
        Files.writeString(target.resolve("SKILL.md"), "---\nname: shared\n---\n", StandardCharsets.UTF_8);
        assumeTrue(symlink(skills.resolve("shared-1"), target), "심볼릭 링크를 만들 수 없는 환경");
        assumeTrue(symlink(skills.resolve("shared-2"), target), "심볼릭 링크를 만들 수 없는 환경");

        // when
        ToolkitScan scan = scan();

        // then
        assertThat(names(scan, ToolkitKind.SKILL, ToolkitScope.PROJECT))
                .containsExactly("commit", "deploy", "shared", "tdd");
    }

    @Test
    @DisplayName("2MB SKILL.md(프런트매터 닫힘 없음)는 64KB 만 읽고 Malformed 다")
    void hugeUnclosedFrontmatterReadsOnly64k() throws IOException {
        // given
        Path huge = Files.createDirectories(
                projectDir.resolve(".claude").resolve("skills").resolve("huge"));
        StringBuilder body = new StringBuilder("---\nname: huge\n");
        while (body.length() < 2 * 1024 * 1024) {
            body.append("description: padding padding padding padding\n");
        }
        Files.writeString(huge.resolve("SKILL.md"), body, StandardCharsets.UTF_8);
        AtomicLong hugeBytes = new AtomicLong();
        FileOpener counting = file -> {
            InputStream in = Files.newInputStream(file);
            return file.getParent().getFileName().toString().equals("huge") ? new CountingStream(in, hugeBytes) : in;
        };

        // when
        ToolkitScan scan = ok(new ClaudeToolkitProvider(counting).scan(query()));

        // then
        assertThat(hugeBytes.get()).isLessThanOrEqualTo(Frontmatter.MAX_BYTES).isPositive();
        assertThat(Frontmatter.MAX_BYTES).isEqualTo(64 * 1024);
        assertThat(scan.warnings())
                .contains(new ToolkitWarning.Malformed(
                        source(PROJECT, ".claude/skills/huge/SKILL.md"), OptionalInt.of(1)));
        assertThat(names(scan, ToolkitKind.SKILL, ToolkitScope.PROJECT)).doesNotContain("huge");
    }

    @Test
    @DisplayName("여러 줄 설명은 한 줄로 펴고 길이 상한에 맞춰 자른다")
    void longDescriptionIsFlattenedAndClipped() throws IOException {
        // given
        Path skill = Files.createDirectories(
                projectDir.resolve(".claude").resolve("skills").resolve("wordy"));
        String longText = "가".repeat(ToolkitItem.MAX_DESCRIPTION_CODE_POINTS + 50);
        Files.writeString(
                skill.resolve("SKILL.md"),
                "---\nname: wordy\ndescription: |\n  first line\n  second\tline\n---\n",
                StandardCharsets.UTF_8);
        Path clipped = Files.createDirectories(
                projectDir.resolve(".claude").resolve("skills").resolve("clipped"));
        Files.writeString(
                clipped.resolve("SKILL.md"), "---\ndescription: " + longText + "\n---\n", StandardCharsets.UTF_8);

        // when
        ToolkitScan scan = scan();

        // then
        assertThat(item(scan, ToolkitKind.SKILL, ToolkitScope.PROJECT, "wordy").description())
                .contains("first line second line");
        String description = item(scan, ToolkitKind.SKILL, ToolkitScope.PROJECT, "clipped")
                .description()
                .orElseThrow();
        assertThat(description.codePointCount(0, description.length()))
                .isEqualTo(ToolkitItem.MAX_DESCRIPTION_CODE_POINTS);
        assertThat(description).endsWith("…");
    }

    private static boolean symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }
}
