package tanager.adapter.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tanager.application.port.out.HistoryLoadNotice;
import tanager.application.port.out.HistoryRepository;
import tanager.application.port.out.HistoryStoreError;
import tanager.application.port.out.LoadedHistory;
import tanager.application.port.out.LoadedWorkspace;
import tanager.application.port.out.StoreAccess;
import tanager.application.port.out.WorkspaceLoadNotice;
import tanager.application.port.out.WorkspaceRepository;
import tanager.domain.history.Alias;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;
import tanager.domain.workspace.WorkspaceSnapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * state.json(ADR-0013, SDD §9.2)을 읽고 쓴다. history 부분은 {@link HistoryRepository}, workspace · window 부분은 {@link
 * WorkspaceRepository} 로 나눠 내보내지만 문서는 하나다 — 읽기 · 잠금 · 손상 판정 · .bak 복구 · 마이그레이션은 문서 단위로 한 번 하고, 어느 쪽
 * 저장이든 두 부분의 마지막 값을 한 번에 원자적으로 쓴다. 앱이 쓰는 파일이라 손상은 .bak → 옆으로 옮기기로 복구하고, 상위 버전 · 다른
 * 인스턴스(state.lock)면 읽기 전용으로 연다. .bak 으로 복구하면 손상 원본을 복사해 두고 state.json 을 .bak 내용으로 원자적으로 바꾼다 — 저장 없이
 * 끝나도 다음 실행이 이력을 잃지 않게. 저장할 때 load 때 읽은 모르는 최상위 키 · window 의 모르는 필드 · 항목별 sources 를 그대로 다시 쓴다(디스크를
 * 다시 읽지 않는다). .bak 은 인스턴스(실행)마다 첫 저장 직전에 한 번 만든다.
 */
public final class JsonStateRepository implements HistoryRepository, WorkspaceRepository, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(JsonStateRepository.class.getName());
    private static final long SCHEMA_VERSION = Migrations.CURRENT;

    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_WORKSPACE = "workspace";
    private static final String KEY_WINDOW = "window";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_SOURCES = "sources";
    private static final String SOURCE_SHELL = "shell";

    private static final DateTimeFormatter CORRUPT_SUFFIX =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final ObjectWriter WRITER = JsonFiles.prettyWriter(MAPPER);

    // 저장 순서: 환경 → 경로. 파일 내용이 Map 순회 순서와 무관하게 결정적이다.
    private static final Comparator<VisitedDirectory> FILE_ORDER = Comparator.comparing(
                    (VisitedDirectory v) -> Environments.orderKey(v.path().env()))
            .thenComparing(v -> v.path().path());

    private final Path file;
    private final Path backup;
    private final Path lockFile;
    private final HistoryPolicy policy;
    private final InstantSource clock;
    private final Mover mover;
    private final Copier copier;

    private Optional<FileChannel> lockChannel = Optional.empty();
    private Optional<FileLock> lock = Optional.empty();
    private Mode mode = new Mode.NotLoaded();
    private ObjectNode otherKeys = MAPPER.createObjectNode();
    private Map<WorkspacePath, ArrayNode> sources = Map.of();
    private boolean backupTaken;
    // 문서의 두 부분: 마지막으로 읽었거나 저장에 성공한 값. 어느 쪽 저장이든 둘을 함께 쓴다
    private WorkHistory lastHistory;
    private Optional<WorkspaceSnapshot> snapshot = Optional.empty();
    // load 때 읽은 window 객체 — 저장할 때 모르는 필드를 보존하는 바탕
    private Optional<ObjectNode> windowBase = Optional.empty();
    // 한 번의 문서 읽기에서 아직 꺼내지 않은 부분. 두 load 가 어느 순서로 와도 문서를 한 번만 읽는다
    private Optional<LoadedHistory> unreadHistory = Optional.empty();
    private Optional<LoadedWorkspace> unreadWorkspace = Optional.empty();

    /** {@code file} 은 보통 {@code PlatformDirs.appData()/state.json}. 만들 때 파일을 건드리지 않는다. */
    public JsonStateRepository(Path file, HistoryPolicy policy, InstantSource clock) {
        this(file, policy, clock, (from, to) -> Files.move(from, to), (from, to) -> Files.copy(from, to));
    }

    // 테스트가 손상 파일 옮기기 · 복사 실패를 재현하려고 쓴다
    JsonStateRepository(Path file, HistoryPolicy policy, InstantSource clock, Mover mover, Copier copier) {
        this.file = Objects.requireNonNull(file, "file");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mover = Objects.requireNonNull(mover, "mover");
        this.copier = Objects.requireNonNull(copier, "copier");
        this.backup = file.resolveSibling(file.getFileName() + ".bak");
        this.lockFile = file.resolveSibling("state.lock");
        this.lastHistory = WorkHistory.empty(policy);
    }

    /** 손상 파일을 옆으로 옮긴다(.bak 도 못 쓸 때). 대상이 이미 있으면 실패해야 한다. */
    @FunctionalInterface
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    /** 손상 파일을 옆에 복사한다(.bak 으로 복구할 때). 대상이 이미 있으면 실패해야 한다. */
    @FunctionalInterface
    interface Copier {
        void copy(Path from, Path to) throws IOException;
    }

    private sealed interface Mode {
        record NotLoaded() implements Mode {}

        record Writable() implements Mode {}

        record ReadOnly(HistoryStoreError.ReadOnlyReason reason) implements Mode {}

        record Closed() implements Mode {}
    }

    /**
     * {@inheritDoc}
     *
     * <p>바로 앞의 {@link #loadWorkspace} 가 읽은 문서의 이력 부분을 아직 꺼내지 않았으면 그것을 돌려주고, 아니면 문서를 다시 읽는다.
     */
    @Override
    public synchronized Result<LoadedHistory, HistoryStoreError> load() {
        Optional<LoadedHistory> unread = unreadHistory;
        unreadHistory = Optional.empty();
        if (unread.isPresent()) {
            return Result.ok(unread.get());
        }
        return readDocument().map(document -> {
            unreadWorkspace = Optional.of(document.workspace());
            return document.history();
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>바로 앞의 {@link #load} 가 읽은 문서의 작업 공간 부분을 아직 꺼내지 않았으면 그것을 돌려주고, 아니면 문서를 다시 읽는다.
     */
    @Override
    public synchronized Result<LoadedWorkspace, HistoryStoreError> loadWorkspace() {
        Optional<LoadedWorkspace> unread = unreadWorkspace;
        unreadWorkspace = Optional.empty();
        if (unread.isPresent()) {
            return Result.ok(unread.get());
        }
        return readDocument().map(document -> {
            unreadHistory = Optional.of(document.history());
            return document.workspace();
        });
    }

    private record Document(LoadedHistory history, LoadedWorkspace workspace) {}

    // 문서 단위로 한 번: 잠금 → 읽기 → 손상이면 .bak 복구 → 마이그레이션 → 두 부분 해석
    private Result<Document, HistoryStoreError> readDocument() {
        // 이번 load 가 끝까지 성공해야 쓸 수 있다 — 실패하면 이전 load 의 다른 최상위 키로 덮어쓰지 않는다
        mode = new Mode.ReadOnly(HistoryStoreError.ReadOnlyReason.LOAD_FAILED);
        unreadHistory = Optional.empty();
        unreadWorkspace = Optional.empty();
        LockState lockState = holdLock();
        Disk primary;
        try {
            primary = read(file);
        } catch (IOException e) {
            return Result.err(new HistoryStoreError.Io(file + ": " + e));
        }
        List<HistoryLoadNotice> notices = new ArrayList<>();
        Optional<ObjectNode> root;
        switch (primary) {
            case Disk.Missing _ -> root = Optional.empty();
            case Disk.Parsed parsed -> root = Optional.of(parsed.root());
            case Disk.Corrupt corrupt -> {
                Result<Optional<ObjectNode>, HistoryStoreError> recovered = recover(corrupt, lockState, notices);
                if (recovered instanceof Result.Err<Optional<ObjectNode>, HistoryStoreError>(var error)) {
                    return Result.err(error);
                }
                root = recovered.value().orElseThrow();
            }
        }
        List<WorkspaceLoadNotice> workspaceNotices = new ArrayList<>();
        if (root.isPresent()) {
            long version = version(root.get());
            Migration.Step migrated = Migrations.upgrade(root.get(), version);
            if (version < SCHEMA_VERSION) {
                LOG.log(
                        System.Logger.Level.INFO,
                        "state.json 을 schemaVersion {0} 에서 {1} 로 읽었다: {2}",
                        version,
                        SCHEMA_VERSION,
                        file);
            }
            root = Optional.of(migrated.root());
            workspaceNotices.addAll(migrated.notices());
        }
        Contents contents = root.map(JsonStateRepository::parse).orElseGet(Contents::empty);
        if (contents.skipped() > 0) {
            notices.add(new HistoryLoadNotice.EntriesSkipped(contents.skipped()));
        }
        if (contents.repaired() > 0) {
            LOG.log(System.Logger.Level.WARNING, "이력 항목 {0} 개의 틀린 값을 고쳐 읽었다: {1}", contents.repaired(), file);
        }
        Optional<HistoryStoreError.ReadOnlyReason> readOnly = Optional.empty();
        if (contents.version() > SCHEMA_VERSION) {
            notices.add(new HistoryLoadNotice.NewerSchema(contents.version()));
            readOnly = Optional.of(HistoryStoreError.ReadOnlyReason.NEWER_SCHEMA);
        }
        switch (lockState) {
            case LockState.Held _ -> {}
            case LockState.HeldElsewhere _ -> {
                notices.add(new HistoryLoadNotice.LockedByOtherInstance());
                readOnly = Optional.of(HistoryStoreError.ReadOnlyReason.OTHER_INSTANCE);
            }
            case LockState.Unavailable unavailable -> {
                notices.add(new HistoryLoadNotice.LockUnavailable(unavailable.detail()));
                readOnly = Optional.of(HistoryStoreError.ReadOnlyReason.LOCK_UNAVAILABLE);
            }
        }
        WorkspaceJson.Read workspace =
                WorkspaceJson.read(root.map(r -> r.get(KEY_WORKSPACE)), root.map(r -> r.get(KEY_WINDOW)));
        workspaceNotices.addAll(workspace.notices());
        mode = readOnly.<Mode>map(Mode.ReadOnly::new).orElseGet(Mode.Writable::new);
        otherKeys = root.map(JsonStateRepository::unknownKeys).orElseGet(MAPPER::createObjectNode);
        windowBase = root.map(r -> r.get(KEY_WINDOW))
                .filter(ObjectNode.class::isInstance)
                .map(w -> ((ObjectNode) w).deepCopy());
        sources = contents.sources();
        lastHistory = WorkHistory.restore(contents.entries(), policy);
        snapshot = workspace.snapshot();
        StoreAccess access = readOnly.isPresent() ? StoreAccess.READ_ONLY : StoreAccess.WRITABLE;
        return Result.ok(new Document(
                new LoadedHistory(lastHistory, access, notices),
                new LoadedWorkspace(snapshot, access, workspaceNotices)));
    }

    @Override
    public synchronized Result<WorkHistory, HistoryStoreError> save(WorkHistory history) {
        Objects.requireNonNull(history, "history");
        Optional<HistoryStoreError> refused = refusal();
        if (refused.isPresent()) {
            return Result.err(refused.get());
        }
        Optional<HistoryStoreError> failed = write(history, snapshot);
        if (failed.isPresent()) {
            return Result.err(failed.get());
        }
        lastHistory = history;
        unreadHistory = Optional.empty();
        return Result.ok(history);
    }

    @Override
    public synchronized Result<WorkspaceSnapshot, HistoryStoreError> saveWorkspace(WorkspaceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Optional<HistoryStoreError> refused = refusal();
        if (refused.isPresent()) {
            return Result.err(refused.get());
        }
        Optional<HistoryStoreError> failed = write(lastHistory, Optional.of(snapshot));
        if (failed.isPresent()) {
            return Result.err(failed.get());
        }
        this.snapshot = Optional.of(snapshot);
        unreadWorkspace = Optional.empty();
        return Result.ok(snapshot);
    }

    // 마지막 load 가 성공해 WRITABLE 로 열었을 때만 쓴다 — 두 포트가 같은 규칙
    private Optional<HistoryStoreError> refusal() {
        return switch (mode) {
            case Mode.NotLoaded _ ->
                Optional.of(new HistoryStoreError.ReadOnly(HistoryStoreError.ReadOnlyReason.NOT_LOADED));
            case Mode.Closed _ -> Optional.of(new HistoryStoreError.ReadOnly(HistoryStoreError.ReadOnlyReason.CLOSED));
            case Mode.ReadOnly readOnly -> Optional.of(new HistoryStoreError.ReadOnly(readOnly.reason()));
            case Mode.Writable _ -> Optional.empty();
        };
    }

    // 실패하면 기존 파일은 그대로이고 메모리의 마지막 값도 바꾸지 않는다
    private Optional<HistoryStoreError> write(WorkHistory history, Optional<WorkspaceSnapshot> workspace) {
        takeBackupOnce();
        try {
            JsonFiles.writeAtomically(file, WRITER.writeValueAsBytes(document(history, workspace)));
            return Optional.empty();
        } catch (IOException | JacksonException e) {
            return Optional.of(new HistoryStoreError.Io(file + ": " + e));
        }
    }

    /** 잠금을 푼다. 예외를 던지지 않는다. 닫은 뒤의 저장은 {@link HistoryStoreError.ReadOnly} 이고, 다시 {@link #load} 하면 잠금을 다시 잡는다. */
    @Override
    public synchronized void close() {
        mode = new Mode.Closed();
        unreadHistory = Optional.empty();
        unreadWorkspace = Optional.empty();
        try {
            if (lock.isPresent()) {
                lock.get().release();
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "이력 잠금을 풀지 못했다: {0}", lockFile);
        }
        lockChannel.ifPresent(JsonStateRepository::closeQuietly);
        lock = Optional.empty();
        lockChannel = Optional.empty();
    }

    // ---- 잠금 ----

    private sealed interface LockState {
        record Held() implements LockState {}

        record HeldElsewhere() implements LockState {}

        record Unavailable(String detail) implements LockState {}
    }

    // 같은 JVM 의 다른 채널이 쥐고 있으면 tryLock 이 OverlappingFileLockException 을 던진다 — 다른 인스턴스와 같게 본다.
    // 잠금 파일을 열 수 없으면 겹치는지 알 수 없으니 읽기만 한다 — 이력을 못 보는 것보다 낫다.
    private LockState holdLock() {
        if (lock.filter(FileLock::isValid).isPresent()) {
            return new LockState.Held();
        }
        FileChannel channel;
        try {
            Path parent = lockFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "이력 잠금 파일을 열지 못했다: {0} ({1})", lockFile, e);
            return new LockState.Unavailable(lockFile + ": " + e);
        }
        Optional<FileLock> acquired;
        try {
            acquired = Optional.ofNullable(channel.tryLock());
        } catch (OverlappingFileLockException e) {
            acquired = Optional.empty();
        } catch (IOException e) {
            closeQuietly(channel);
            LOG.log(System.Logger.Level.WARNING, "이력 잠금을 잡지 못했다: {0} ({1})", lockFile, e);
            return new LockState.Unavailable(lockFile + ": " + e);
        }
        if (acquired.isEmpty()) {
            closeQuietly(channel);
            return new LockState.HeldElsewhere();
        }
        lockChannel = Optional.of(channel);
        lock = acquired;
        return new LockState.Held();
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "이력 잠금 파일을 닫지 못했다: {0}", e);
        }
    }

    // ---- 읽기 ----

    private sealed interface Disk {
        record Missing() implements Disk {}

        // bytes 는 .bak 으로 복구할 때 state.json 에 그대로 되돌려 쓴다
        record Parsed(ObjectNode root, byte[] bytes) implements Disk {}

        record Corrupt(String detail) implements Disk {}
    }

    // 읽지 못하면(권한 등) IOException, 내용이 틀리면 Corrupt.
    private static Disk read(Path path) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (NoSuchFileException e) {
            return new Disk.Missing();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(bytes);
        } catch (JacksonException e) {
            return new Disk.Corrupt(String.valueOf(e.getOriginalMessage()));
        }
        if (!(root instanceof ObjectNode object)) {
            return new Disk.Corrupt("최상위 값이 객체가 아니다");
        }
        // 새 버전은 history 모양을 바꿀 수 있다 — 손상으로 보고 옮기면 새 버전의 파일을 망가뜨린다
        if (newerVersion(object).isPresent()) {
            return new Disk.Parsed(object, bytes);
        }
        return structureProblem(object).<Disk>map(Disk.Corrupt::new).orElseGet(() -> new Disk.Parsed(object, bytes));
    }

    // 없으면 1 — schemaVersion 이 없는 파일은 v1 로 보고 올린다
    private static long version(ObjectNode root) {
        return Optional.ofNullable(root.get(KEY_SCHEMA_VERSION))
                .flatMap(JsonStateRepository::integralVersion)
                .orElse(1L);
    }

    private static Optional<Long> newerVersion(ObjectNode root) {
        return Optional.ofNullable(root.get(KEY_SCHEMA_VERSION))
                .flatMap(JsonStateRepository::integralVersion)
                .filter(v -> v > SCHEMA_VERSION);
    }

    // 정수 값이면 형식(정수 · 2.0 같은 소수)과 범위에 상관없이 버전으로 본다 — int 만 보면 3000000000 · 2.0 인 새 버전 파일을 손상으로 옮긴다.
    // long 을 넘는 값은 부호에 따라 Long.MAX_VALUE · MIN_VALUE 로 줄인다. 1 과 비교한 판정은 같다.
    private static Optional<Long> integralVersion(JsonNode version) {
        if (!version.canConvertToExactIntegral()) {
            return Optional.empty();
        }
        if (version.canConvertToLong()) {
            return Optional.of(version.longValue());
        }
        return Optional.of(version.bigIntegerValue().signum() > 0 ? Long.MAX_VALUE : Long.MIN_VALUE);
    }

    // 항목 하나가 틀린 것은 그 항목만 건너뛴다. 뼈대가 틀리면 파일 전체를 믿을 수 없다.
    private static Optional<String> structureProblem(ObjectNode root) {
        JsonNode version = root.get(KEY_SCHEMA_VERSION);
        if (version != null && integralVersion(version).filter(v -> v >= 1).isEmpty()) {
            return Optional.of("schemaVersion 이 1 이상의 정수가 아니다");
        }
        JsonNode history = root.get(KEY_HISTORY);
        if (history == null) {
            return Optional.empty();
        }
        if (!(history instanceof ObjectNode historyObject)) {
            return Optional.of("history 가 객체가 아니다");
        }
        JsonNode entries = historyObject.get(KEY_ENTRIES);
        if (entries != null && !(entries instanceof ArrayNode)) {
            return Optional.of("history.entries 가 배열이 아니다");
        }
        return Optional.empty();
    }

    // .bak 이 정상이면 손상 원본을 옆에 복사한 뒤 state.json 을 .bak 내용으로 원자적으로 바꾼다. 어느 단계에서 멈춰도(전원 차단 포함)
    // state.json 은 손상 원본이거나 복구한 내용이다 — "state.json 없음 + 정상 .bak" 이면 다음 실행이 빈 이력을 열고, 그 다음 실행의 첫
    // 저장이 좋은 .bak 을 덮어쓴다. .bak 도 못 쓰면 원본을 옮긴다. .bak 을 읽다 입출력 오류가 나면 정상인지 알 수 없으니 아무것도 건드리지
    // 않고 Io 다. 잠금을 쥐지 않았으면(다른 인스턴스 · 잠금 파일 열기 실패) 아무것도 바꾸지 않는다.
    // backupTaken 은 원본을 옆에 두었거나 .bak 으로 복구했을 때만 세운다 — 이번 실행의 .bak 은 이미 정해졌다.
    private Result<Optional<ObjectNode>, HistoryStoreError> recover(
            Disk.Corrupt corrupt, LockState lockState, List<HistoryLoadNotice> notices) {
        LOG.log(System.Logger.Level.WARNING, "이력 파일이 손상됐다: {0} ({1})", file, corrupt.detail());
        Disk fallback;
        try {
            fallback = read(backup);
        } catch (IOException e) {
            return Result.err(new HistoryStoreError.Io(".bak 을 읽지 못했다 " + backup + ": " + e));
        }
        boolean held = lockState instanceof LockState.Held;
        if (fallback instanceof Disk.Parsed parsed) {
            Optional<String> keptAs = Optional.empty();
            if (held) {
                Result<Path, HistoryStoreError> restored = restoreFromBackup(parsed.bytes());
                if (restored instanceof Result.Err<Path, HistoryStoreError>(var error)) {
                    return Result.err(error);
                }
                keptAs =
                        Optional.of(restored.value().orElseThrow().getFileName().toString());
                backupTaken = true;
            }
            notices.add(new HistoryLoadNotice.RecoveredFromBackup(keptAs));
            return Result.ok(Optional.of(parsed.root()));
        }
        if (!held) {
            return Result.ok(Optional.empty());
        }
        Path aside;
        try {
            aside = asideName();
            mover.move(file, aside);
        } catch (IOException e) {
            return Result.err(new HistoryStoreError.Io("손상된 이력 파일을 옮기지 못했다 " + file + ": " + e));
        }
        backupTaken = true;
        notices.add(new HistoryLoadNotice.CorruptMovedAside(aside.getFileName().toString()));
        return Result.ok(Optional.empty());
    }

    // 성공하면 손상 원본을 복사해 둔 경로.
    private Result<Path, HistoryStoreError> restoreFromBackup(byte[] bytes) {
        Path aside;
        try {
            aside = asideName();
            copier.copy(file, aside);
        } catch (IOException e) {
            return Result.err(new HistoryStoreError.Io("손상된 이력 파일을 복사해 두지 못했다 " + file + ": " + e));
        }
        try {
            JsonFiles.writeAtomically(file, bytes);
        } catch (IOException e) {
            return Result.err(new HistoryStoreError.Io(
                    "백업으로 되돌려 쓰지 못했다. 손상된 이력 파일은 제자리에 그대로 있다 " + file + "(복사본 " + aside.getFileName() + "): " + e));
        }
        return Result.ok(aside);
    }

    // 같은 초에 이미 옆에 둔 파일이 있으면 -1, -2 … 를 붙인다. 이름을 고른 뒤 생긴 파일은 mover · copier 가 실패시킨다.
    private Path asideName() {
        String base = file.getFileName() + ".corrupt-" + CORRUPT_SUFFIX.format(clock.instant());
        Path aside = file.resolveSibling(base);
        for (int n = 1; Files.exists(aside, LinkOption.NOFOLLOW_LINKS); n++) {
            aside = file.resolveSibling(base + "-" + n);
        }
        return aside;
    }

    private record Contents(
            List<VisitedDirectory> entries,
            int skipped,
            int repaired,
            long version,
            Map<WorkspacePath, ArrayNode> sources) {
        static Contents empty() {
            return new Contents(List.of(), 0, 0, SCHEMA_VERSION, Map.of());
        }
    }

    // 상위 버전은 뼈대 검사를 건너뛰었으므로 history · entries 모양이 틀리면 항목 0개로 읽는다.
    private static Contents parse(ObjectNode root) {
        JsonNode versionNode = root.get(KEY_SCHEMA_VERSION);
        long version = Optional.ofNullable(versionNode)
                .flatMap(JsonStateRepository::integralVersion)
                .orElse(SCHEMA_VERSION);
        if (!(root.get(KEY_HISTORY) instanceof ObjectNode history)
                || !(history.get(KEY_ENTRIES) instanceof ArrayNode entries)) {
            return new Contents(List.of(), 0, 0, version, Map.of());
        }
        List<VisitedDirectory> out = new ArrayList<>();
        Map<WorkspacePath, ArrayNode> sources = new HashMap<>();
        int skipped = 0;
        int repaired = 0;
        for (JsonNode node : entries) {
            Optional<ReadEntry> entry = entry(node);
            if (entry.isEmpty()) {
                skipped++;
                continue;
            }
            ReadEntry read = entry.get();
            out.add(read.directory());
            if (read.repaired()) {
                repaired++;
            }
            sources.merge(read.directory().path(), read.sources(), JsonStateRepository::union);
        }
        return new Contents(out, skipped, repaired, version, Map.copyOf(sources));
    }

    private record ReadEntry(VisitedDirectory directory, boolean repaired, ArrayNode sources) {}

    // 해석할 수 없는 값(경로 · 환경 · 시각 · 횟수 · 플래그)이 있으면 항목을 건너뛴다. 불변식 위반(처음 > 마지막, 고정이면서 숨김)과
    // 틀린 별칭은 고쳐서 읽는다 — 값 하나 때문에 경로를 잃지 않게. 항목 안의 모르는 필드는 보존하지 않는다(ADR-0013).
    private static Optional<ReadEntry> entry(JsonNode node) {
        if (!(node instanceof ObjectNode o)) {
            return Optional.empty();
        }
        Optional<ShellEnvironment> env = Environments.read(o.get("env"));
        Optional<String> rawPath = Environments.string(o.get("path"));
        Optional<Instant> first = instant(o.get("firstVisited"));
        Optional<Instant> last = instant(o.get("lastVisited"));
        JsonNode count = o.get("visitCount");
        JsonNode pinned = o.get("pinned");
        JsonNode hidden = o.get("hidden");
        if (env.isEmpty()
                || rawPath.isEmpty()
                || first.isEmpty()
                || last.isEmpty()
                || count == null
                || !count.isInt()
                || count.intValue() < 1
                || pinned == null
                || !pinned.isBoolean()
                || hidden == null
                || !hidden.isBoolean()) {
            return Optional.empty();
        }
        Optional<WorkspacePath> path =
                WorkspacePath.of(env.get(), rawPath.get()).value();
        if (path.isEmpty()) {
            return Optional.empty();
        }
        Optional<Optional<Alias>> alias = alias(o.get("alias"));
        boolean firstAfterLast = first.get().isAfter(last.get());
        boolean pinnedAndHidden = pinned.booleanValue() && hidden.booleanValue();
        VisitedDirectory directory = new VisitedDirectory(
                path.get(),
                firstAfterLast ? last.get() : first.get(),
                last.get(),
                count.intValue(),
                pinned.booleanValue(),
                alias.orElseGet(Optional::empty),
                hidden.booleanValue() && !pinnedAndHidden);
        boolean repaired = firstAfterLast || pinnedAndHidden || alias.isEmpty();
        // sources 가 없는 항목은 저장할 때 [shell] 이 된다. 합칠 때도 같게 봐야 shell 출처를 잃지 않는다.
        ArrayNode entrySources = o.get(KEY_SOURCES) instanceof ArrayNode array ? array.deepCopy() : shellOnly();
        return Optional.of(new ReadEntry(directory, repaired, entrySources));
    }

    private static Optional<Instant> instant(JsonNode node) {
        try {
            return Environments.string(node).map(Instant::parse);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    // 바깥 empty = 틀린 값(별칭만 버림), 안쪽 empty = 별칭 없음(null · 키 없음).
    private static Optional<Optional<Alias>> alias(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.of(Optional.empty());
        }
        return Environments.string(node).flatMap(s -> Alias.of(s).value()).map(Optional::of);
    }

    // 대소문자만 다른 Windows 경로처럼 합쳐지는 항목은 sources 도 합친다(먼저 나온 순서 유지).
    private static ArrayNode union(ArrayNode first, ArrayNode later) {
        ArrayNode merged = first.deepCopy();
        for (JsonNode source : later) {
            boolean present = false;
            for (JsonNode existing : merged) {
                present = present || existing.equals(source);
            }
            if (!present) {
                merged.add(source.deepCopy());
            }
        }
        return merged;
    }

    // 이 앱이 해석하지 않는 최상위 키 — 저장할 때 그대로 다시 쓴다
    private static ObjectNode unknownKeys(ObjectNode root) {
        ObjectNode rest = root.deepCopy();
        rest.remove(List.of(KEY_SCHEMA_VERSION, KEY_HISTORY, KEY_WORKSPACE, KEY_WINDOW));
        return rest;
    }

    // ---- 쓰기 ----

    // 실패해도 저장은 계속한다 — 백업이 없다고 새 방문을 잃으면 안 된다.
    private void takeBackupOnce() {
        if (backupTaken) {
            return;
        }
        backupTaken = true;
        try {
            JsonFiles.writeAtomically(backup, Files.readAllBytes(file));
        } catch (NoSuchFileException e) {
            // 처음 저장이라 남길 것이 없다
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "이력 백업을 만들지 못했다: {0} ({1})", backup, e);
        }
    }

    // 작업 공간이 없으면 load 때 읽은 window 를 그대로 둔다 — 이력만 저장해도 창 상태를 잃지 않게
    private ObjectNode document(WorkHistory history, Optional<WorkspaceSnapshot> workspace) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION);
        ArrayNode entries = root.putObject(KEY_HISTORY).putArray(KEY_ENTRIES);
        List<VisitedDirectory> sorted =
                history.entries().values().stream().sorted(FILE_ORDER).toList();
        for (VisitedDirectory v : sorted) {
            entries.add(entryNode(v));
        }
        if (workspace.isPresent()) {
            root.set(KEY_WORKSPACE, WorkspaceJson.workspace(workspace.get().workspace()));
            root.set(KEY_WINDOW, WorkspaceJson.window(workspace.get().window(), windowBase));
        } else {
            windowBase.ifPresent(window -> root.set(KEY_WINDOW, window.deepCopy()));
        }
        for (Map.Entry<String, JsonNode> other : otherKeys.properties()) {
            root.set(other.getKey(), other.getValue().deepCopy());
        }
        return root;
    }

    private ObjectNode entryNode(VisitedDirectory v) {
        ObjectNode node = MAPPER.createObjectNode();
        Environments.write(node.putObject("env"), v.path().env());
        node.put("path", v.path().path());
        node.put("firstVisited", v.firstVisited().toString());
        node.put("lastVisited", v.lastVisited().toString());
        node.put("visitCount", v.visitCount());
        node.put("pinned", v.pinned());
        v.alias().ifPresentOrElse(a -> node.put("alias", a.value()), () -> node.putNull("alias"));
        node.put("hidden", v.hidden());
        Optional<ArrayNode> known = Optional.ofNullable(sources.get(v.path()));
        node.set(KEY_SOURCES, known.map(ArrayNode::deepCopy).orElseGet(JsonStateRepository::shellOnly));
        return node;
    }

    private static ArrayNode shellOnly() {
        return MAPPER.createArrayNode().add(SOURCE_SHELL);
    }
}
