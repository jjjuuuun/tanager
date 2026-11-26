package dev.tanager.domain.history;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 이력과 실행 중 세션을 사이드바의 "환경 → 폴더 → 세션" 트리로 투영하는 순수 함수.
 *
 * <p>방문 기록도 세션도 없고 자식이 폴더 하나뿐인 폴더는 자식과 합친다(루트 포함, {@code /} + {@code mnt} → {@code /mnt}). 폴더는 문자열이
 * 아니라 {@link WorkspacePath} 로 묶으므로 Windows 에서 대소문자만 다른 경로는 같은 노드다. 항목 · 세션 수 N 에 대해 O(N log N)이고 경로
 * 깊이에 비례한다(정렬 한 번 + 경로마다 조상 사슬).
 *
 * <p>검색어가 있으면 보이는 방문 중 검색어에 맞은 폴더({@code matched})와 그 조상만 남기고, 남은 폴더의 세션만 매단 뒤 압축을 다시 계산한다.
 * 검색어에 구분자({@code /} · {@code \})가 없으면 폴더 이름(루트는 경로) · 별칭에서, 있으면 전체 경로 · 별칭에서 찾는다 — 검색어
 * {@code home} 이 {@code /home} 아래 전부를 고르지 않게. 고정됨 섹션도 같은 규칙으로 거른다.
 */
public final class DirectoryTree {

    private static final String UNC_PREFIX = "\\\\";

    private static final Comparator<SessionLeaf> SESSION_ORDER = Comparator.comparingInt(SessionLeaf::number)
            .thenComparing(s -> s.pane().value());

    // 대소문자 무시로 먼저 가르고, 같으면 자연 순서로 가려 항상 같은 순서가 되게 한다.
    private static final Comparator<String> NAME_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    // 라벨을 세그먼트 목록 단위로 비교한다 — 구분자가 순서에 끼지 않아 Windows 와 WSL 이 같은 순서를 낸다.
    private static final Comparator<List<String>> SEGMENTS_ORDER =
            lexicographic(String.CASE_INSENSITIVE_ORDER).thenComparing(lexicographic(Comparator.naturalOrder()));

    private static final Comparator<Built> FOLDER_ORDER =
            Comparator.comparing(Built::kind).thenComparing(Built::key, SEGMENTS_ORDER);

    // 라벨이 같으면 경로 문자열로 가려 입력 순서와 상관없이 같은 순서가 되게 한다.
    private static final Comparator<PinnedEntry> PINNED_ORDER = Comparator.comparing(PinnedEntry::label, NAME_ORDER)
            .thenComparing(p -> p.entry().path().path());

    private DirectoryTree() {}

    /** {@code history} 와 {@code sessions} 를 {@code query} 조건으로 투영한다. 입력은 바꾸지 않는다. */
    public static HistoryTree project(WorkHistory history, List<SessionLeaf> sessions, TreeQuery query) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(query, "query");

        Optional<Needle> needle = Needle.of(query.text());
        Forest forest = new Forest();
        // 정렬해 넣어 방문 순서 · Map 순회 순서와 상관없이 같은 표기를 고른다.
        List<VisitedDirectory> entries = new ArrayList<>(history.entries().values());
        entries.sort(Comparator.comparing(v -> v.path().path()));
        List<VisitedDirectory> visible = new ArrayList<>();
        for (VisitedDirectory v : entries) {
            // 검색 결과와 상관없이 환경을 등록해 검색어를 칠 때 환경 노드가 깜빡이지 않게 한다.
            forest.environment(v.path().env());
            if (query.showHidden() || !v.hidden()) {
                visible.add(v);
            }
        }
        Optional<Set<WorkspacePath>> kept = needle.map(n -> matchesAndAncestors(visible, n));
        for (VisitedDirectory v : visible) {
            if (kept.map(k -> k.contains(v.path())).orElse(true)) {
                Node node = forest.node(v.path());
                node.visit = Optional.of(v);
                node.matched = needle.map(n -> n.matches(v)).orElse(false);
            }
        }
        List<SessionLeaf> ordered = new ArrayList<>(sessions);
        ordered.sort(SESSION_ORDER);
        for (SessionLeaf s : ordered) {
            if (needle.isPresent()) {
                // 거를지는 방문만으로 정했다 — 세션은 남은 폴더에만 매달고 새 폴더를 만들지 않는다.
                forest.environment(s.env());
                s.cwd().flatMap(forest::existing).ifPresent(n -> n.sessions.add(s));
            } else if (s.cwd().isPresent()) {
                forest.node(s.cwd().get()).sessions.add(s);
            } else {
                forest.environment(s.env()).sessions.add(s);
            }
        }

        List<EnvironmentNode> environments = new ArrayList<>();
        for (ShellEnvironment env : environmentOrder(query.environmentOrder(), forest.environments.keySet())) {
            EnvBuilder e = forest.environments.getOrDefault(env, new EnvBuilder());
            environments.add(new EnvironmentNode(env, children(e.sessions, e.roots.values())));
        }
        return new HistoryTree(pinned(entries, needle), environments);
    }

    // 맞은 방문 경로와 그 조상 전부. 이미 넣은 조상에서 멈춰 공통 조상을 다시 걷지 않는다.
    private static Set<WorkspacePath> matchesAndAncestors(List<VisitedDirectory> visible, Needle needle) {
        Set<WorkspacePath> kept = new HashSet<>();
        for (VisitedDirectory v : visible) {
            if (!needle.matches(v)) {
                continue;
            }
            Optional<WorkspacePath> p = Optional.of(v.path());
            while (p.isPresent() && kept.add(p.get())) {
                p = p.get().parent();
            }
        }
        return kept;
    }

    // 고정 항목은 숨길 수 없으므로 showHidden 과 상관없다.
    private static List<PinnedEntry> pinned(List<VisitedDirectory> entries, Optional<Needle> needle) {
        List<PinnedEntry> result = new ArrayList<>();
        for (VisitedDirectory v : entries) {
            if (v.pinned() && needle.map(n -> n.matches(v)).orElse(true)) {
                result.add(new PinnedEntry(v, pinnedLabel(v), needle.isPresent()));
            }
        }
        result.sort(PINNED_ORDER);
        return result;
    }

    private static String pinnedLabel(VisitedDirectory v) {
        return v.alias().map(Alias::value).orElseGet(() -> name(v.path()));
    }

    // 마지막 세그먼트, 루트면 경로 전체.
    private static String name(WorkspacePath path) {
        List<String> segments = path.segments();
        return segments.isEmpty() ? path.path() : segments.getLast();
    }

    // 순서 목록의 환경(비어 있어도) → 목록에 없는 환경은 이름순(같은 이름이면 Windows 먼저).
    private static List<ShellEnvironment> environmentOrder(List<ShellEnvironment> order, Set<ShellEnvironment> seen) {
        Set<ShellEnvironment> result = new LinkedHashSet<>(order);
        List<ShellEnvironment> unlisted = new ArrayList<>();
        for (ShellEnvironment env : seen) {
            if (!result.contains(env)) {
                unlisted.add(env);
            }
        }
        unlisted.sort(Comparator.comparing(DirectoryTree::environmentName, NAME_ORDER)
                .thenComparing(env -> env instanceof ShellEnvironment.Wsl));
        result.addAll(unlisted);
        return List.copyOf(result);
    }

    private static String environmentName(ShellEnvironment env) {
        return switch (env) {
            case ShellEnvironment.Wsl wsl -> wsl.distro();
            case ShellEnvironment.Windows _ -> "Windows";
        };
    }

    private static List<TreeNode> children(List<SessionLeaf> sessions, Collection<Node> folders) {
        List<TreeNode> result = new ArrayList<>();
        for (SessionLeaf s : sessions) {
            result.add(new TreeNode.Session(s));
        }
        List<Built> built = new ArrayList<>();
        for (Node n : folders) {
            built.add(compressed(n));
        }
        built.sort(FOLDER_ORDER);
        for (Built b : built) {
            result.add(b.folder());
        }
        return result;
    }

    // 방문도 세션도 없이 폴더 하나만 품은 노드는 자식과 합쳐 한 줄로 만든다.
    // 라벨은 끝 노드의 표시 경로에서 잘라 만든다 — 중간 노드의 표기를 이어 붙이면 path() 와 대소문자가 어긋날 수 있다.
    private static Built compressed(Node start) {
        Node end = start;
        while (end.visit.isEmpty() && end.sessions.isEmpty() && end.children.size() == 1) {
            end = end.children.values().iterator().next();
        }
        WorkspacePath shown = end.displayPath();
        List<TreeNode> children = children(end.sessions, end.children.values());
        if (start.path.parent().isEmpty()) {
            return rootFolder(new TreeNode.Folder(shown, shown.path(), end.visit, end.matched, children));
        }
        List<String> segments = shown.segments();
        List<String> labelSegments = segments.subList(start.path.segments().size() - 1, segments.size());
        String label = String.join(separator(shown.env()), labelSegments);
        return new Built(
                new TreeNode.Folder(shown, label, end.visit, end.matched, children), RootKind.NONE, labelSegments);
    }

    // 환경 바로 아래 루트는 종류(드라이브 → UNC → WSL '/') → 루트 이름(드라이브 글자 · UNC 호스트와 공유) → 라벨 세그먼트 순.
    private static Built rootFolder(TreeNode.Folder folder) {
        WorkspacePath path = folder.path();
        String root = path.root();
        RootKind kind;
        List<String> key = new ArrayList<>();
        switch (path.env()) {
            case ShellEnvironment.Wsl _ -> kind = RootKind.WSL;
            case ShellEnvironment.Windows _ -> {
                if (root.startsWith(UNC_PREFIX)) {
                    int sep = root.indexOf('\\', UNC_PREFIX.length());
                    kind = RootKind.UNC;
                    key.add(root.substring(UNC_PREFIX.length(), sep));
                    key.add(root.substring(sep + 1));
                } else {
                    kind = RootKind.DRIVE;
                    key.add(root.substring(0, 1));
                }
            }
        }
        key.addAll(path.segments());
        return new Built(folder, kind, key);
    }

    private static String separator(ShellEnvironment env) {
        return switch (env) {
            case ShellEnvironment.Wsl _ -> "/";
            case ShellEnvironment.Windows _ -> "\\";
        };
    }

    private static <T> Comparator<List<T>> lexicographic(Comparator<? super T> element) {
        return (a, b) -> {
            int n = Math.min(a.size(), b.size());
            for (int i = 0; i < n; i++) {
                int c = element.compare(a.get(i), b.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(a.size(), b.size());
        };
    }

    /** 형제 폴더 정렬 순위. 루트가 아닌 형제는 모두 {@code NONE} 이라 라벨 세그먼트로만 가린다. */
    private enum RootKind {
        NONE,
        DRIVE,
        UNC,
        WSL
    }

    /** 만든 폴더와 정렬 키. */
    private record Built(TreeNode.Folder folder, RootKind kind, List<String> key) {}

    /**
     * 정규화한 검색어(NFC · {@code Locale.ROOT} 소문자). {@code spansSegments} 는 구분자가 들어 있어 전체 경로에서 찾는지다. 구분자는 서로
     * 바꾸지 않는다 — WSL 경로를 {@code \} 로 찾지 않는다.
     */
    private record Needle(String text, boolean spansSegments) {

        static Optional<Needle> of(String raw) {
            String text = normalized(raw.strip());
            if (text.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Needle(text, text.indexOf('/') >= 0 || text.indexOf('\\') >= 0));
        }

        boolean matches(VisitedDirectory v) {
            String target = spansSegments ? v.path().path() : name(v.path());
            return contains(target) || v.alias().map(a -> contains(a.value())).orElse(false);
        }

        private boolean contains(String haystack) {
            return normalized(haystack).contains(text);
        }

        private static String normalized(String s) {
            return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        }
    }

    /** 투영 중에만 쓰는 가변 작업대. 함수 밖으로 나가지 않는다. */
    private static final class Forest {
        private final Map<ShellEnvironment, EnvBuilder> environments = new LinkedHashMap<>();
        private final Map<WorkspacePath, Node> nodes = new HashMap<>();

        EnvBuilder environment(ShellEnvironment env) {
            return environments.computeIfAbsent(env, e -> new EnvBuilder());
        }

        // 경로의 노드를 찾거나, 없으면 이미 있는 조상까지 거슬러 올라가며 만든다.
        Node node(WorkspacePath path) {
            Node existing = nodes.get(path);
            if (existing != null) {
                return existing;
            }
            Node created = new Node(path);
            nodes.put(path, created);
            Optional<WorkspacePath> parent = path.parent();
            if (parent.isPresent()) {
                node(parent.get()).children.put(path, created);
            } else {
                environment(path.env()).roots.put(path, created);
            }
            return created;
        }

        Optional<Node> existing(WorkspacePath path) {
            return Optional.ofNullable(nodes.get(path));
        }
    }

    private static final class EnvBuilder {
        private final List<SessionLeaf> sessions = new ArrayList<>();
        private final Map<WorkspacePath, Node> roots = new HashMap<>();
    }

    private static final class Node {
        // 노드를 처음 만든 경로의 표기. 정렬해 넣으므로 그 아래 보이는 방문 중 문자열 순서로 가장 앞선 것(없으면 가장 앞선 세션 cwd)이다.
        private final WorkspacePath path;
        private Optional<VisitedDirectory> visit = Optional.empty();
        private boolean matched;
        private final List<SessionLeaf> sessions = new ArrayList<>();
        private final Map<WorkspacePath, Node> children = new HashMap<>();

        Node(WorkspacePath path) {
            this.path = path;
        }

        // 방문 노드는 방문 기록의 표기를 쓴다.
        WorkspacePath displayPath() {
            return visit.map(VisitedDirectory::path).orElse(path);
        }
    }
}
