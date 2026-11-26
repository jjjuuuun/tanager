package dev.tanager.domain.history;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * 아니라 {@link WorkspacePath} 로 묶으므로 Windows 에서 대소문자만 다른 경로는 같은 노드다. EDT 에서 부를 수 있도록 입력 크기에 선형이다.
 */
public final class DirectoryTree {

    private static final Comparator<SessionLeaf> SESSION_ORDER = Comparator.comparingInt(SessionLeaf::number);

    // 대소문자 무시(대문자로 접어 비교)로 먼저 가르고, 같으면 일반 문자열 순서로 가려 항상 같은 순서가 되게 한다.
    // 대문자로 접는 이유: 소문자로 접으면 '\'(0x5C) 가 영문자보다 앞서 UNC 루트가 드라이브 루트보다 먼저 온다.
    private static final Comparator<String> LABEL_ORDER =
            Comparator.comparing((String s) -> s.toUpperCase(Locale.ROOT)).thenComparing(Comparator.naturalOrder());

    private DirectoryTree() {}

    /** {@code history} 와 {@code sessions} 를 {@code query} 조건으로 투영한다. 입력은 바꾸지 않는다. */
    public static HistoryTree project(WorkHistory history, List<SessionLeaf> sessions, TreeQuery query) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(query, "query");

        Forest forest = new Forest();
        // 정렬해 넣어 방문 순서 · Map 순회 순서와 상관없이 같은 표기를 고른다.
        List<VisitedDirectory> entries = new ArrayList<>(history.entries().values());
        entries.sort(Comparator.comparing(v -> v.path().path()));
        for (VisitedDirectory v : entries) {
            forest.environment(v.path().env());
            if (query.showHidden() || !v.hidden()) {
                forest.node(v.path()).visit(v);
            }
        }
        List<SessionLeaf> ordered = new ArrayList<>(sessions);
        ordered.sort(SESSION_ORDER);
        for (SessionLeaf s : ordered) {
            if (s.cwd().isPresent()) {
                forest.node(s.cwd().get()).sessions.add(s);
            } else {
                forest.environment(s.env()).sessions.add(s);
            }
        }

        List<EnvironmentNode> environments = new ArrayList<>();
        for (ShellEnvironment env : environmentOrder(query.environmentOrder(), forest.environments.keySet())) {
            EnvBuilder e = forest.environments.getOrDefault(env, new EnvBuilder());
            environments.add(new EnvironmentNode(env, children(e.sessions, e.roots.values(), true)));
        }
        return new HistoryTree(List.of(), environments);
    }

    // 순서 목록의 환경(비어 있어도) → 목록에 없는 환경은 이름순.
    private static List<ShellEnvironment> environmentOrder(List<ShellEnvironment> order, Set<ShellEnvironment> seen) {
        Set<ShellEnvironment> result = new LinkedHashSet<>(order);
        List<ShellEnvironment> unlisted = new ArrayList<>();
        for (ShellEnvironment env : seen) {
            if (!result.contains(env)) {
                unlisted.add(env);
            }
        }
        unlisted.sort(Comparator.comparing(DirectoryTree::environmentName, LABEL_ORDER)
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

    private static List<TreeNode> children(
            List<SessionLeaf> sessions, Iterable<Node> folders, boolean underEnvironment) {
        List<TreeNode> result = new ArrayList<>();
        for (SessionLeaf s : sessions) {
            result.add(new TreeNode.Session(s));
        }
        List<TreeNode.Folder> built = new ArrayList<>();
        for (Node n : folders) {
            built.add(compressed(n, underEnvironment));
        }
        built.sort(Comparator.comparing(TreeNode.Folder::label, LABEL_ORDER));
        result.addAll(built);
        return result;
    }

    // 방문도 세션도 없이 폴더 하나만 품은 노드는 자식과 합쳐 한 줄로 만든다.
    private static TreeNode.Folder compressed(Node start, boolean isRoot) {
        Node node = start;
        String label = isRoot ? node.path.root() : lastSegment(node.path);
        while (node.visit.isEmpty() && node.sessions.isEmpty() && node.children.size() == 1) {
            node = node.children.values().iterator().next();
            label = append(label, lastSegment(node.path), separator(node.path.env()));
        }
        return new TreeNode.Folder(
                node.path, label, node.visit, false, children(node.sessions, node.children.values(), false));
    }

    // 드라이브 · WSL 루트는 구분자로 끝나 그대로 붙이고, UNC 루트와 일반 라벨은 구분자를 넣는다.
    private static String append(String label, String segment, String separator) {
        return label.endsWith(separator) ? label + segment : label + separator + segment;
    }

    private static String lastSegment(WorkspacePath path) {
        return path.segments().getLast();
    }

    private static String separator(ShellEnvironment env) {
        return switch (env) {
            case ShellEnvironment.Wsl _ -> "/";
            case ShellEnvironment.Windows _ -> "\\";
        };
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
    }

    private static final class EnvBuilder {
        private final List<SessionLeaf> sessions = new ArrayList<>();
        private final Map<WorkspacePath, Node> roots = new HashMap<>();
    }

    private static final class Node {
        private WorkspacePath path;
        private Optional<VisitedDirectory> visit = Optional.empty();
        private final List<SessionLeaf> sessions = new ArrayList<>();
        private final Map<WorkspacePath, Node> children = new HashMap<>();

        Node(WorkspacePath path) {
            this.path = path;
        }

        // 방문 노드는 방문 기록의 표기를 쓴다.
        void visit(VisitedDirectory v) {
            this.visit = Optional.of(v);
            this.path = v.path();
        }
    }
}
