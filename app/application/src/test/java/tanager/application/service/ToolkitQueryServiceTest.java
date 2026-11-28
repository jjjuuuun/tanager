package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.QueryToolkitUseCase.Freshness;
import tanager.application.port.out.FakeHostFiles;
import tanager.application.port.out.FakeScheduler;
import tanager.application.port.out.HomeError;
import tanager.application.port.out.ScanStamp;
import tanager.application.port.out.ScriptedToolkitProvider;
import tanager.application.port.out.ToolkitConfig;
import tanager.application.port.out.ToolkitError;
import tanager.application.port.out.ToolkitProvider;
import tanager.application.port.out.ToolkitQuery;
import tanager.application.port.out.ToolkitScan;
import tanager.application.state.ManualExecutor;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.ProviderId;
import tanager.domain.toolkit.SourceRef;
import tanager.domain.toolkit.ToolkitItem;
import tanager.domain.toolkit.ToolkitKind;
import tanager.domain.toolkit.ToolkitScope;
import tanager.domain.toolkit.ToolkitView;
import tanager.domain.toolkit.ToolkitWarning;

/** 도구함 조회(#209). 읽기는 {@link ManualExecutor}, 시간 제한은 {@link FakeScheduler} 로 결정적으로 돌린다. */
class ToolkitQueryServiceTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final WorkspacePath WORK = path(WIN, "C:\\work\\a");
    private static final WorkspacePath WIN_HOME = path(WIN, "C:\\Users\\me");
    private static final WorkspacePath WSL_WORK = path(UBUNTU, "/home/me/work");
    private static final ScanStamp STAMP_1 = stamp(Instant.parse("2026-11-28T00:00:00Z"));
    private static final ScanStamp STAMP_2 = stamp(Instant.parse("2026-11-28T00:01:00Z"));

    private final ManualExecutor io = new ManualExecutor();
    private final FakeScheduler timer = new FakeScheduler();
    private final FakeHostFiles hostFiles = new FakeHostFiles().withHome(WIN, WIN_HOME);
    private final ScriptedToolkitProvider a = ScriptedToolkitProvider.windowsOnly("a");
    private final ScriptedToolkitProvider b = ScriptedToolkitProvider.windowsOnly("b");
    private ToolkitConfig config = ToolkitConfig.defaults();

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static ScanStamp stamp(Instant modified) {
        return new ScanStamp(Map.of(Path.of("C:\\work\\a\\.claude"), Optional.of(modified)));
    }

    private static ToolkitItem item(ProviderId provider, String name) {
        return new ToolkitItem(
                provider,
                ToolkitKind.SKILL,
                name,
                ToolkitScope.PROJECT,
                Optional.empty(),
                new SourceRef(WORK),
                List.of(),
                false);
    }

    private static Result<ToolkitScan, ToolkitError> scanOf(ToolkitItem... items) {
        return Result.ok(new ToolkitScan(List.of(items), List.of(), Optional.empty()));
    }

    private ToolkitQueryService service(ToolkitProvider... providers) {
        return new ToolkitQueryService(List.of(providers), hostFiles, () -> config, io, timer);
    }

    private static ToolkitView done(CompletionStage<ToolkitView> stage) {
        CompletableFuture<ToolkitView> future = stage.toCompletableFuture();
        assertThat(future).as("조회가 끝났다").isDone();
        return future.join();
    }

    private ToolkitView queryNow(ToolkitQueryService service, WorkspacePath path, Freshness freshness) {
        CompletionStage<ToolkitView> stage = service.query(path, freshness);
        io.runAll();
        return done(stage);
    }

    @Test
    @DisplayName("두 공급자의 항목이 한 view 에 모이고 공급자 순서는 등록 순이다")
    void mergesProvidersInRegistrationOrder() {
        // given
        a.withScan(scanOf(item(a.id(), "alpha")));
        b.withScan(scanOf(item(b.id(), "beta")));
        ToolkitQueryService service = service(a, b);

        // when
        ToolkitView view = queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(view.path()).isEqualTo(WORK);
        assertThat(view.items()).extracting(ToolkitItem::name).containsExactly("alpha", "beta");
        assertThat(view.warnings()).isEmpty();
        assertThat(view.providers()).containsExactly(a.id(), b.id());
        assertThat(service.providers(WIN)).containsExactly(a.id(), b.id());
    }

    @Test
    @DisplayName("공급자는 그 환경의 홈과 호스트 경로 변환을 쿼리로 받는다")
    void passesHomeAndHostTranslation() {
        // given
        ToolkitQueryService service = service(a);

        // when
        queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        ToolkitQuery query = a.scans().getFirst();
        assertThat(query.path()).isEqualTo(WORK);
        assertThat(query.home()).contains(WIN_HOME);
        assertThat(query.host().apply(WORK)).contains(Path.of("C:\\work\\a"));
    }

    @Test
    @DisplayName("설정 toolkit.providers 에 적은 공급자만 부른다")
    void callsOnlyConfiguredProviders() {
        // given
        config = new ToolkitConfig(List.of("b"), true);
        ToolkitQueryService service = service(a, b);

        // when
        queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(a.scans()).isEmpty();
        assertThat(b.scans()).hasSize(1);
        assertThat(service.providers(WIN)).containsExactly(b.id());
    }

    @Test
    @DisplayName("설정 toolkit.providers 가 빈 배열이면 등록된 공급자를 모두 부른다")
    void emptyConfigMeansAllProviders() {
        // given
        config = new ToolkitConfig(List.of(), true);
        ToolkitQueryService service = service(a, b);

        // when
        queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(a.scans()).hasSize(1);
        assertThat(b.scans()).hasSize(1);
    }

    @Test
    @DisplayName("그 환경을 지원하지 않는 공급자는 부르지 않고 칩에도 없다")
    void skipsProvidersThatDoNotSupportEnvironment() {
        // given
        hostFiles.withHome(UBUNTU, path(UBUNTU, "/home/me"));
        ScriptedToolkitProvider anywhere = ScriptedToolkitProvider.everywhere("c");
        ToolkitQueryService service = service(a, anywhere);

        // when
        queryNow(service, WSL_WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(a.scans()).isEmpty();
        assertThat(anywhere.scans()).hasSize(1);
        assertThat(service.providers(UBUNTU)).containsExactly(anywhere.id());
    }

    @Test
    @DisplayName("부를 공급자가 없으면 홈을 묻지 않고 빈 view 를 준다 — WSL 을 깨우지 않는다")
    void noProvidersMeansEmptyViewWithoutAskingHome() {
        // given
        ToolkitQueryService service = service(a);

        // when
        ToolkitView view = queryNow(service, WSL_WORK, Freshness.FORCE);

        // then
        assertThat(view.items()).isEmpty();
        assertThat(view.warnings()).isEmpty();
        assertThat(hostFiles.homeRequests()).isEmpty();
    }

    @Test
    @DisplayName("공급자 하나가 err 면 그 공급자의 ProviderFailed 경고 하나만 생기고 다른 공급자의 항목은 그대로다")
    void failedProviderBecomesWarning() {
        // given
        a.withScan(Result.err(new ToolkitError.Unreachable()));
        b.withScan(scanOf(item(b.id(), "beta")));
        ToolkitQueryService service = service(a, b);

        // when
        ToolkitView view = queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(view.warnings()).containsExactly(new ToolkitWarning.ProviderFailed(a.id()));
        assertThat(view.items()).extracting(ToolkitItem::name).containsExactly("beta");
    }

    @Test
    @DisplayName("공급자가 예외를 던져도 그 공급자의 ProviderFailed 경고로 끝난다")
    void throwingProviderBecomesWarning() {
        // given
        a.throwing(new IllegalStateException("버그"));
        b.withScan(scanOf(item(b.id(), "beta")));
        ToolkitQueryService service = service(a, b);

        // when
        ToolkitView view = queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(view.warnings()).containsExactly(new ToolkitWarning.ProviderFailed(a.id()));
        assertThat(view.items()).extracting(ToolkitItem::name).containsExactly("beta");
    }

    @Test
    @DisplayName("공급자가 남긴 파일 경고는 view 에 그대로 실린다")
    void keepsProviderWarnings() {
        // given
        ToolkitWarning unreadable = new ToolkitWarning.Unreadable(new SourceRef(WORK));
        a.withScan(Result.ok(new ToolkitScan(List.of(item(a.id(), "alpha")), List.of(unreadable), Optional.empty())));
        ToolkitQueryService service = service(a);

        // when
        ToolkitView view = queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(view.warnings()).containsExactly(unreadable);
        assertThat(view.items()).hasSize(1);
    }

    @Test
    @DisplayName("홈을 모르면 HomeUnknown 경고를 싣고 쿼리의 home 은 empty 다")
    void unknownHomeWarnsAndQueriesProjectOnly() {
        // given
        hostFiles.withHomeError(new HomeError.TimedOut(WIN));
        a.withScan(scanOf(item(a.id(), "alpha")));
        ToolkitQueryService service = service(a);

        // when
        ToolkitView view = queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(view.warnings()).containsExactly(new ToolkitWarning.HomeUnknown(WIN));
        assertThat(a.scans().getFirst().home()).isEmpty();
        assertThat(view.items()).hasSize(1);
    }

    @Test
    @DisplayName("같은 경로를 두 번 REUSE_IF_UNCHANGED 로 조회하고 지문이 같으면 scan 은 한 번이다")
    void reusesScanWhenStampUnchanged() {
        // given
        a.withScan(scanOf(item(a.id(), "alpha"))).withStamp(Optional.of(STAMP_1));
        ToolkitQueryService service = service(a);
        queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // when
        ToolkitView second = queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(a.scans()).hasSize(1);
        assertThat(a.stamps()).hasSize(1);
        assertThat(second.items()).extracting(ToolkitItem::name).containsExactly("alpha");
    }

    @Test
    @DisplayName("지문이 바뀌면 다시 scan 한다")
    void rescansWhenStampChanged() {
        // given
        a.withScan(scanOf(item(a.id(), "alpha"))).withStamp(Optional.of(STAMP_1));
        ToolkitQueryService service = service(a);
        queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);
        a.withScan(scanOf(item(a.id(), "alpha2"))).withStamp(Optional.of(STAMP_2));

        // when
        ToolkitView second = queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(a.scans()).hasSize(2);
        assertThat(second.items()).extracting(ToolkitItem::name).containsExactly("alpha2");
    }

    @Test
    @DisplayName("FORCE 는 지문과 관계없이 다시 scan 한다")
    void forceAlwaysRescans() {
        // given
        a.withScan(scanOf(item(a.id(), "alpha"))).withStamp(Optional.of(STAMP_1));
        ToolkitQueryService service = service(a);
        queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // when
        queryNow(service, WORK, Freshness.FORCE);

        // then
        assertThat(a.scans()).hasSize(2);
        assertThat(a.stamps()).isEmpty();
    }

    @Test
    @DisplayName("지문을 주지 않는 공급자는 캐시하지 않는다")
    void doesNotCacheWithoutStamp() {
        // given
        a.withScan(scanOf(item(a.id(), "alpha")));
        ToolkitQueryService service = service(a);
        queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // when
        queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(a.scans()).hasSize(2);
    }

    @Test
    @DisplayName("실패한 scan 은 캐시하지 않는다")
    void doesNotCacheFailures() {
        // given
        a.withScan(Result.err(new ToolkitError.Failed())).withStamp(Optional.of(STAMP_1));
        ToolkitQueryService service = service(a);
        queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);
        a.withScan(scanOf(item(a.id(), "alpha")));

        // when
        ToolkitView second = queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(a.scans()).hasSize(2);
        assertThat(second.items()).hasSize(1);
        assertThat(second.warnings()).isEmpty();
    }

    @Test
    @DisplayName("캐시는 64 경로까지다 — 가장 오래 쓰지 않은 경로부터 버린다")
    void cacheKeepsAtMost64Paths() {
        // given
        a.withStamp(Optional.of(STAMP_1));
        ToolkitQueryService service = service(a);
        List<WorkspacePath> paths = new ArrayList<>();
        for (int i = 0; i <= 64; i++) {
            paths.add(path(WIN, "C:\\work\\p" + i));
        }
        for (WorkspacePath p : paths) {
            queryNow(service, p, Freshness.REUSE_IF_UNCHANGED);
        }

        // when
        queryNow(service, paths.get(64), Freshness.REUSE_IF_UNCHANGED);
        queryNow(service, paths.get(0), Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(a.scans()).as("65 경로 + 버려진 첫 경로 다시 읽기").hasSize(66);
    }

    @Test
    @DisplayName("같은 경로의 조회 둘이 겹치면 scan 은 한 번이고 두 응답이 같은 view 다")
    void joinsInFlightQueryForSamePath() {
        // given
        a.withScan(scanOf(item(a.id(), "alpha")));
        ToolkitQueryService service = service(a);

        // when
        CompletionStage<ToolkitView> first = service.query(WORK, Freshness.REUSE_IF_UNCHANGED);
        CompletionStage<ToolkitView> second = service.query(WORK, Freshness.FORCE);
        io.runAll();

        // then
        assertThat(a.scans()).hasSize(1);
        assertThat(done(second)).isEqualTo(done(first));
    }

    @Test
    @DisplayName("끝난 조회 뒤의 같은 경로 조회는 새로 읽는다")
    void startsNewQueryAfterPreviousCompleted() {
        // given
        ToolkitQueryService service = service(a);
        queryNow(service, WORK, Freshness.FORCE);

        // when
        queryNow(service, WORK, Freshness.FORCE);

        // then
        assertThat(a.scans()).hasSize(2);
    }

    @Test
    @DisplayName("공급자가 11초 걸리면 10초에 그 공급자만 ProviderFailed 로 완료되고 늦은 결과는 버린다")
    void slowProviderTimesOutAtTenSeconds() {
        // given — b 가 먼저 끝나고, a 는 scan 안에서 시간을 11초 보낸다
        b.withScan(scanOf(item(b.id(), "beta")));
        a.withScan(scanOf(item(a.id(), "late")));
        AtomicReference<CompletableFuture<ToolkitView>> pending = new AtomicReference<>();
        List<Boolean> doneAt = new ArrayList<>();
        a.duringScan(() -> {
            timer.advance(Duration.ofSeconds(10).minusMillis(1));
            doneAt.add(pending.get().isDone());
            timer.advance(Duration.ofMillis(1));
            doneAt.add(pending.get().isDone());
            timer.advance(Duration.ofSeconds(1));
        });
        ToolkitQueryService service = service(b, a);

        // when
        pending.set(service.query(WORK, Freshness.FORCE).toCompletableFuture());
        io.runAll();

        // then
        assertThat(doneAt).as("9.999초에는 기다리고 10초에 끝난다").containsExactly(false, true);
        ToolkitView view = done(pending.get());
        assertThat(view.warnings()).containsExactly(new ToolkitWarning.ProviderFailed(a.id()));
        assertThat(view.items()).extracting(ToolkitItem::name).containsExactly("beta");
    }

    @Test
    @DisplayName("제때 끝난 공급자의 시간 제한 예약은 취소한다")
    void cancelsTimeoutWhenProviderFinishes() {
        // given
        ToolkitQueryService service = service(a, b);

        // when
        queryNow(service, WORK, Freshness.FORCE);

        // then
        assertThat(timer.pendingCount()).isZero();
    }

    @Test
    @DisplayName("공급자가 다른 공급자 id 의 항목을 돌려주면 그 항목은 빠진다")
    void dropsItemsOfForeignProvider() {
        // given
        a.withScan(scanOf(item(a.id(), "mine"), item(new ProviderId("other"), "theirs")));
        ToolkitQueryService service = service(a);

        // when
        ToolkitView view = queryNow(service, WORK, Freshness.REUSE_IF_UNCHANGED);

        // then
        assertThat(view.items()).extracting(ToolkitItem::name).containsExactly("mine");
    }

    @Test
    @DisplayName("query 는 부른 스레드에서 scan 하지 않고 실행기에 맡긴다")
    void scansOffCallingThread() {
        // given
        ToolkitQueryService service = service(a);

        // when
        CompletionStage<ToolkitView> stage = service.query(WORK, Freshness.FORCE);

        // then
        assertThat(a.scans()).as("실행기를 돌리기 전").isEmpty();
        assertThat(hostFiles.homeRequests()).as("홈 묻기도 실행기에서").isEmpty();
        assertThat(stage.toCompletableFuture()).isNotDone();
        io.runAll();
        assertThat(a.scans()).hasSize(1);
        assertThat(stage.toCompletableFuture()).isDone();
    }

    @Test
    @DisplayName("같은 id 의 공급자가 또 등록되면 앞의 것만 쓴다")
    void keepsFirstProviderOfDuplicateId() {
        // given
        ScriptedToolkitProvider twin = ScriptedToolkitProvider.windowsOnly("a");
        ToolkitQueryService service = service(a, twin);

        // when
        queryNow(service, WORK, Freshness.FORCE);

        // then
        assertThat(service.providers(WIN)).containsExactly(a.id());
        assertThat(a.scans()).hasSize(1);
        assertThat(twin.scans()).isEmpty();
    }

    @Test
    @DisplayName("항목 원본의 호스트 경로는 HostFiles 로 바꾼다")
    void hostFileTranslatesSource() {
        // given
        ToolkitQueryService service = service(a);
        WorkspacePath wslFile = path(UBUNTU, "/home/me/.claude/CLAUDE.md");

        // when
        Optional<Path> host = service.hostFile(new SourceRef(wslFile));

        // then
        assertThat(host).contains(Path.of("\\\\wsl.localhost\\Ubuntu\\home\\me\\.claude\\CLAUDE.md"));
    }
}
