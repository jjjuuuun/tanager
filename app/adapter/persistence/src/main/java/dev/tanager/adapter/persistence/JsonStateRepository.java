package dev.tanager.adapter.persistence;

import dev.tanager.application.port.out.HistoryLoadNotice;
import dev.tanager.application.port.out.HistoryRepository;
import dev.tanager.application.port.out.HistoryStoreError;
import dev.tanager.application.port.out.LoadedHistory;
import dev.tanager.application.port.out.StoreAccess;
import dev.tanager.domain.history.Alias;
import dev.tanager.domain.history.HistoryPolicy;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.history.VisitedDirectory;
import dev.tanager.domain.history.WorkHistory;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.shared.Result;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * state.json 의 history 부분(ADR-0013, SDD §9.2)을 읽고 쓴다. 앱이 쓰는 파일이라 손상은 .bak → 옆으로 옮기기로 복구하고, 상위 버전 ·
 * 다른 인스턴스(state.lock)면 읽기 전용으로 연다. 저장할 때 load 때 읽은 다른 최상위 키(workspace · window …)와 항목별 sources 를 그대로
 * 다시 쓴다(디스크를 다시 읽지 않는다). .bak 은 인스턴스(실행)마다 첫 저장 직전에 한 번 만든다.
 */
public final class JsonStateRepository implements HistoryRepository, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(JsonStateRepository.class.getName());
    private static final int SCHEMA_VERSION = 1;

    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_SOURCES = "sources";
    private static final String KIND_WSL = "wsl";
    private static final String KIND_WINDOWS = "windows";
    private static final String SOURCE_SHELL = "shell";

    private static final DateTimeFormatter CORRUPT_SUFFIX =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final ObjectWriter WRITER = JsonFiles.prettyWriter(MAPPER);

    // 저장 순서: 환경 → 경로. 파일 내용이 Map 순회 순서와 무관하게 결정적이다.
    private static final Comparator<VisitedDirectory> FILE_ORDER = Comparator.comparing(
                    (VisitedDirectory v) -> envOrderKey(v.path().env()))
            .thenComparing(v -> v.path().path());

    private final Path file;
    private final Path backup;
    private final Path lockFile;
    private final HistoryPolicy policy;
    private final InstantSource clock;
    private final Mover mover;

    private Optional<FileChannel> lockChannel = Optional.empty();
    private Optional<FileLock> lock = Optional.empty();
    private Mode mode = new Mode.NotLoaded();
    private ObjectNode otherKeys = MAPPER.createObjectNode();
    private Map<WorkspacePath, ArrayNode> sources = Map.of();
    private boolean backupTaken;

    /** {@code file} 은 보통 {@code PlatformDirs.appData()/state.json}. 만들 때 파일을 건드리지 않는다. */
    public JsonStateRepository(Path file, HistoryPolicy policy, InstantSource clock) {
        this(file, policy, clock, (from, to) -> Files.move(from, to));
    }

    // 테스트가 손상 파일 옮기기 실패를 재현하려고 쓴다
    JsonStateRepository(Path file, HistoryPolicy policy, InstantSource clock, Mover mover) {
        this.file = Objects.requireNonNull(file, "file");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mover = Objects.requireNonNull(mover, "mover");
        this.backup = file.resolveSibling(file.getFileName() + ".bak");
        this.lockFile = file.resolveSibling("state.lock");
    }

    /** 손상 파일을 옆으로 옮긴다. 대상이 이미 있으면 실패해야 한다. */
    @FunctionalInterface
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    private sealed interface Mode {
        record NotLoaded() implements Mode {}

        record Writable() implements Mode {}

        record ReadOnly(String reason) implements Mode {}

        record Closed() implements Mode {}
    }

    @Override
    public synchronized Result<LoadedHistory, HistoryStoreError> load() {
        // 이번 load 가 끝까지 성공해야 쓸 수 있다 — 실패하면 이전 load 의 다른 최상위 키로 덮어쓰지 않는다
        mode = new Mode.ReadOnly("불러오지 못해 쓰지 않는다");
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
                // 손상된 파일을 .bak 으로 복사하면 좋은 백업을 잃는다
                backupTaken = true;
                Result<Optional<ObjectNode>, HistoryStoreError> recovered = recover(corrupt, lockState, notices);
                if (recovered instanceof Result.Err<Optional<ObjectNode>, HistoryStoreError>(var error)) {
                    return Result.err(error);
                }
                root = recovered.value().orElseThrow();
            }
        }
        Contents contents = root.map(JsonStateRepository::parse).orElseGet(Contents::empty);
        if (contents.skipped() > 0) {
            notices.add(new HistoryLoadNotice.EntriesSkipped(contents.skipped()));
        }
        if (contents.repaired() > 0) {
            LOG.log(System.Logger.Level.WARNING, "이력 항목 {0} 개의 틀린 값을 고쳐 읽었다: {1}", contents.repaired(), file);
        }
        Optional<String> readOnly = Optional.empty();
        if (contents.version() > SCHEMA_VERSION) {
            notices.add(new HistoryLoadNotice.NewerSchema(contents.version()));
            readOnly = Optional.of("새 버전(schemaVersion " + contents.version() + ")이 쓴 파일이다");
        }
        switch (lockState) {
            case LockState.Held _ -> {}
            case LockState.HeldElsewhere _ -> {
                notices.add(new HistoryLoadNotice.LockedByOtherInstance());
                readOnly = Optional.of("다른 Tanager 가 쓰고 있다");
            }
            case LockState.Unavailable unavailable -> {
                notices.add(new HistoryLoadNotice.LockUnavailable(unavailable.detail()));
                readOnly = Optional.of("잠금 파일을 열 수 없다");
            }
        }
        mode = readOnly.<Mode>map(Mode.ReadOnly::new).orElseGet(Mode.Writable::new);
        otherKeys = root.map(JsonStateRepository::withoutHistory).orElseGet(MAPPER::createObjectNode);
        sources = contents.sources();
        StoreAccess access = readOnly.isPresent() ? StoreAccess.READ_ONLY : StoreAccess.WRITABLE;
        return Result.ok(new LoadedHistory(WorkHistory.restore(contents.entries(), policy), access, notices));
    }

    @Override
    public synchronized Result<WorkHistory, HistoryStoreError> save(WorkHistory history) {
        Objects.requireNonNull(history, "history");
        switch (mode) {
            case Mode.NotLoaded _ -> {
                return Result.err(new HistoryStoreError.ReadOnly("불러오기 전에는 쓰지 않는다"));
            }
            case Mode.Closed _ -> {
                return Result.err(new HistoryStoreError.ReadOnly("닫힌 저장소에는 쓰지 않는다"));
            }
            case Mode.ReadOnly readOnly -> {
                return Result.err(new HistoryStoreError.ReadOnly(readOnly.reason()));
            }
            case Mode.Writable _ -> {}
        }
        takeBackupOnce();
        try {
            JsonFiles.writeAtomically(file, WRITER.writeValueAsBytes(document(history)));
            return Result.ok(history);
        } catch (IOException | JacksonException e) {
            return Result.err(new HistoryStoreError.Io(file + ": " + e));
        }
    }

    /** 잠금을 푼다. 예외를 던지지 않는다. 닫은 뒤의 저장은 {@link HistoryStoreError.ReadOnly} 이고, 다시 {@link #load} 하면 잠금을 다시 잡는다. */
    @Override
    public synchronized void close() {
        mode = new Mode.Closed();
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

        record Parsed(ObjectNode root) implements Disk {}

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
            return new Disk.Parsed(object);
        }
        return structureProblem(object).<Disk>map(Disk.Corrupt::new).orElseGet(() -> new Disk.Parsed(object));
    }

    private static Optional<Integer> newerVersion(ObjectNode root) {
        JsonNode version = root.get(KEY_SCHEMA_VERSION);
        return version != null && version.isInt() && version.intValue() > SCHEMA_VERSION
                ? Optional.of(version.intValue())
                : Optional.empty();
    }

    // 항목 하나가 틀린 것은 그 항목만 건너뛴다. 뼈대가 틀리면 파일 전체를 믿을 수 없다.
    private static Optional<String> structureProblem(ObjectNode root) {
        JsonNode version = root.get(KEY_SCHEMA_VERSION);
        if (version != null && !(version.isInt() && version.intValue() >= 1)) {
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

    // .bak 이 정상이면 그것을 쓰고 손상 원본은 옆으로 옮긴다 — 첫 저장이 원본을 덮어쓰지 않게. .bak 도 못 쓰면 원본만 옮긴다.
    // 잠금을 쥐지 않았으면(다른 인스턴스 · 잠금 파일 열기 실패) 아무것도 옮기지 않는다.
    private Result<Optional<ObjectNode>, HistoryStoreError> recover(
            Disk.Corrupt corrupt, LockState lockState, List<HistoryLoadNotice> notices) {
        LOG.log(System.Logger.Level.WARNING, "이력 파일이 손상됐다: {0} ({1})", file, corrupt.detail());
        Disk fallback;
        try {
            fallback = read(backup);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "이력 백업을 읽지 못했다: {0}", backup);
            fallback = new Disk.Missing();
        }
        boolean mayMove = lockState instanceof LockState.Held;
        Optional<String> moved = Optional.empty();
        if (mayMove) {
            try {
                moved = Optional.of(moveAside());
            } catch (IOException e) {
                return Result.err(new HistoryStoreError.Io("손상된 이력 파일을 옮기지 못했다 " + file + ": " + e));
            }
        }
        if (fallback instanceof Disk.Parsed(ObjectNode root)) {
            notices.add(new HistoryLoadNotice.RecoveredFromBackup(moved));
            return Result.ok(Optional.of(root));
        }
        moved.ifPresent(name -> notices.add(new HistoryLoadNotice.CorruptMovedAside(name)));
        return Result.ok(Optional.empty());
    }

    // 같은 초에 이미 옮긴 파일이 있으면 -1, -2 … 를 붙인다. 이름을 고른 뒤 생긴 파일은 mover 가 실패시킨다.
    private String moveAside() throws IOException {
        String base = file.getFileName() + ".corrupt-" + CORRUPT_SUFFIX.format(clock.instant());
        Path aside = file.resolveSibling(base);
        for (int n = 1; Files.exists(aside, LinkOption.NOFOLLOW_LINKS); n++) {
            aside = file.resolveSibling(base + "-" + n);
        }
        mover.move(file, aside);
        return aside.getFileName().toString();
    }

    private record Contents(
            List<VisitedDirectory> entries,
            int skipped,
            int repaired,
            int version,
            Map<WorkspacePath, ArrayNode> sources) {
        static Contents empty() {
            return new Contents(List.of(), 0, 0, SCHEMA_VERSION, Map.of());
        }
    }

    // 상위 버전은 뼈대 검사를 건너뛰었으므로 history · entries 모양이 틀리면 항목 0개로 읽는다.
    private static Contents parse(ObjectNode root) {
        JsonNode versionNode = root.get(KEY_SCHEMA_VERSION);
        int version = versionNode == null ? SCHEMA_VERSION : versionNode.intValue();
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
            read.sources().ifPresent(s -> sources.merge(read.directory().path(), s, JsonStateRepository::union));
        }
        return new Contents(out, skipped, repaired, version, Map.copyOf(sources));
    }

    private record ReadEntry(VisitedDirectory directory, boolean repaired, Optional<ArrayNode> sources) {}

    // 해석할 수 없는 값(경로 · 환경 · 시각 · 횟수 · 플래그)이 있으면 항목을 건너뛴다. 불변식 위반(처음 > 마지막, 고정이면서 숨김)과
    // 틀린 별칭은 고쳐서 읽는다 — 값 하나 때문에 경로를 잃지 않게. 항목 안의 모르는 필드는 보존하지 않는다(ADR-0013).
    private static Optional<ReadEntry> entry(JsonNode node) {
        if (!(node instanceof ObjectNode o)) {
            return Optional.empty();
        }
        Optional<ShellEnvironment> env = environment(o.get("env"));
        Optional<String> rawPath = string(o.get("path"));
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
        Optional<ArrayNode> entrySources =
                o.get(KEY_SOURCES) instanceof ArrayNode array ? Optional.of(array.deepCopy()) : Optional.empty();
        return Optional.of(new ReadEntry(directory, repaired, entrySources));
    }

    private static Optional<ShellEnvironment> environment(JsonNode node) {
        if (node == null) {
            return Optional.empty();
        }
        Optional<String> kind = string(node.get("kind"));
        if (kind.equals(Optional.of(KIND_WINDOWS))) {
            return Optional.of(new ShellEnvironment.Windows());
        }
        if (kind.equals(Optional.of(KIND_WSL))) {
            return string(node.get("distro")).filter(d -> !d.isBlank()).map(ShellEnvironment.Wsl::new);
        }
        return Optional.empty();
    }

    private static Optional<String> string(JsonNode node) {
        return node != null && node.isString() ? Optional.of(node.stringValue()) : Optional.empty();
    }

    private static Optional<Instant> instant(JsonNode node) {
        try {
            return string(node).map(Instant::parse);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    // 바깥 empty = 틀린 값(별칭만 버림), 안쪽 empty = 별칭 없음(null · 키 없음).
    private static Optional<Optional<Alias>> alias(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.of(Optional.empty());
        }
        return string(node).flatMap(s -> Alias.of(s).value()).map(Optional::of);
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

    private static ObjectNode withoutHistory(ObjectNode root) {
        ObjectNode rest = root.deepCopy();
        rest.remove(KEY_SCHEMA_VERSION);
        rest.remove(KEY_HISTORY);
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

    private ObjectNode document(WorkHistory history) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION);
        ArrayNode entries = root.putObject(KEY_HISTORY).putArray(KEY_ENTRIES);
        List<VisitedDirectory> sorted =
                history.entries().values().stream().sorted(FILE_ORDER).toList();
        for (VisitedDirectory v : sorted) {
            entries.add(entryNode(v));
        }
        for (Map.Entry<String, JsonNode> other : otherKeys.properties()) {
            root.set(other.getKey(), other.getValue().deepCopy());
        }
        return root;
    }

    private ObjectNode entryNode(VisitedDirectory v) {
        ObjectNode node = MAPPER.createObjectNode();
        ObjectNode env = node.putObject("env");
        switch (v.path().env()) {
            case ShellEnvironment.Wsl wsl -> env.put("kind", KIND_WSL).put("distro", wsl.distro());
            case ShellEnvironment.Windows _ -> env.put("kind", KIND_WINDOWS);
        }
        node.put("path", v.path().path());
        node.put("firstVisited", v.firstVisited().toString());
        node.put("lastVisited", v.lastVisited().toString());
        node.put("visitCount", v.visitCount());
        node.put("pinned", v.pinned());
        v.alias().ifPresentOrElse(a -> node.put("alias", a.value()), () -> node.putNull("alias"));
        node.put("hidden", v.hidden());
        Optional<ArrayNode> known = Optional.ofNullable(sources.get(v.path()));
        node.set(
                KEY_SOURCES,
                known.map(ArrayNode::deepCopy)
                        .orElseGet(() -> MAPPER.createArrayNode().add(SOURCE_SHELL)));
        return node;
    }

    private static String envOrderKey(ShellEnvironment env) {
        return switch (env) {
            case ShellEnvironment.Windows _ -> KIND_WINDOWS;
            case ShellEnvironment.Wsl wsl -> KIND_WSL + ":" + wsl.distro();
        };
    }
}
