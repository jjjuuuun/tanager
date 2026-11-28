package tanager.adapter.toolkit.claude;

import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tanager.application.port.out.ScanStamp;
import tanager.application.port.out.ToolkitError;
import tanager.application.port.out.ToolkitProvider;
import tanager.application.port.out.ToolkitQuery;
import tanager.application.port.out.ToolkitScan;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.ProviderId;
import tanager.domain.toolkit.ScopePrecedence;
import tanager.domain.toolkit.ToolkitKind;
import tanager.domain.toolkit.ToolkitScope;

/**
 * Claude Code 의 구성(FR-TK-02 · FR-TK-03 · FR-TK-05, SDD §7.1 · §7.2, ADR-0019). 프로젝트({@code <경로>/.claude/})와 그 환경의
 * 홈({@code ~/.claude/})에서 종류마다 {@link ClaudeReader} 로 읽고, 같은 이름은 {@link #PRECEDENCE} 로 가린다. 읽기 전용이다 — 파일을
 * 쓰지 않는다. 읽은 내용은 로그에 남기지 않는다.
 */
public final class ClaudeToolkitProvider implements ToolkitProvider {

    static final ProviderId ID = new ProviderId("claude-code");

    /**
     * 같은 이름일 때 이기는 범위(앞이 이긴다). Claude Code 문서(2026-11-28 확인):
     *
     * <ul>
     *   <li>스킬 — "Enterprise over personal, and personal over project":
     *       https://code.claude.com/docs/en/skills#resolve-skills-that-share-a-name
     *   <li>커맨드 — 커맨드는 스킬로 합쳐졌고 같은 위치 규칙을 따른다(문서에 커맨드끼리의 순서가 따로 없어 스킬 표를 쓴다):
     *       https://code.claude.com/docs/en/skills
     *   <li>서브에이전트 — managed(1) &gt; 프로젝트(3) &gt; 사용자(4): https://code.claude.com/docs/en/sub-agents
     *   <li>메모리 · 규칙 — 모두 합쳐 적용되므로 표에 넣지 않는다(가리지 않는다): https://code.claude.com/docs/en/memory
     * </ul>
     */
    static final ScopePrecedence PRECEDENCE = new ScopePrecedence(Map.of(
            ToolkitKind.SKILL, List.of(ToolkitScope.MANAGED, ToolkitScope.USER, ToolkitScope.PROJECT),
            ToolkitKind.COMMAND, List.of(ToolkitScope.MANAGED, ToolkitScope.USER, ToolkitScope.PROJECT),
            ToolkitKind.SUBAGENT, List.of(ToolkitScope.MANAGED, ToolkitScope.PROJECT, ToolkitScope.USER)));

    private static final System.Logger LOG = System.getLogger(ClaudeToolkitProvider.class.getName());

    /** 종류마다의 읽기. MCP · 훅 · 권한은 여기에 한 줄씩 더한다. */
    private static final List<ClaudeReader> READERS =
            List.of(new SkillReader(), new CommandReader(), new AgentReader(), new MemoryReader(), new RuleReader());

    private final FileOpener opener;

    /** {@code ServiceLoader} 용. */
    public ClaudeToolkitProvider() {
        this(Files::newInputStream);
    }

    ClaudeToolkitProvider(FileOpener opener) {
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public boolean supports(ShellEnvironment env) {
        return switch (env) {
            case ShellEnvironment.Windows _ -> true;
            case ShellEnvironment.Wsl _ -> true;
        };
    }

    @Override
    public Result<ToolkitScan, ToolkitError> scan(ToolkitQuery query) {
        ClaudeLayout layout = layoutOf(query);
        if (!layout.reachable()) {
            return Result.err(new ToolkitError.Unreachable());
        }
        long started = System.nanoTime();
        Collector out = Collector.scanning(opener);
        readAll(layout, out);
        ToolkitScan scan = out.scan(PRECEDENCE);
        LOG.log(
                Level.DEBUG,
                "Claude 도구함: 항목 {0} 개, 경고 {1} 개, 입력 {2} 개, {3} ms",
                scan.items().size(),
                scan.warnings().size(),
                scan.stamp().map(s -> s.inputs().size()).orElse(0),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        return Result.ok(scan);
    }

    @Override
    public Optional<ScanStamp> stamp(ToolkitQuery query) {
        ClaudeLayout layout = layoutOf(query);
        if (!layout.reachable()) {
            return Optional.empty();
        }
        Collector out = Collector.stamping();
        readAll(layout, out);
        return Optional.of(out.stamp());
    }

    private static ClaudeLayout layoutOf(ToolkitQuery query) {
        return new ClaudeLayout(query.path(), query.home(), query.host());
    }

    private static void readAll(ClaudeLayout layout, Collector out) {
        for (ClaudeReader reader : READERS) {
            reader.read(layout, out);
        }
    }
}
