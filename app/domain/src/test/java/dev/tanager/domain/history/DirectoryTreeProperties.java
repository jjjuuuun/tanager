package dev.tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.domain.shared.PaneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class DirectoryTreeProperties {

    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("U");
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    /** 투영 입력 — 대소문자 중복 없는 이력 항목과 세션. */
    record Input(List<VisitedDirectory> entries, List<SessionLeaf> sessions) {}

    private record SessionSpec(ShellEnvironment env, Optional<WorkspacePath> cwd, int number) {}

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static Arbitrary<WorkspacePath> wslPaths() {
        return segments().map(l -> path(WSL, "/" + String.join("/", l)));
    }

    private static Arbitrary<WorkspacePath> winPaths() {
        return segments()
                .flatMap(l -> Arbitraries.of("C:\\", "d:\\", "\\\\srv\\Share\\", "\\\\SRV\\share\\", "\\\\a-b\\s\\")
                        .map(root -> path(WIN, root + String.join("\\", l))));
    }

    private static Arbitrary<List<String>> segments() {
        return Arbitraries.of("a", "B", "b", "src", "Src", "x y", "proj", "_archive", "a0")
                .list()
                .ofMaxSize(4);
    }

    private static Arbitrary<VisitedDirectory> entries() {
        return Combinators.combine(Arbitraries.oneOf(wslPaths(), winPaths()), Arbitraries.of(false, true))
                .as((p, hidden) -> new VisitedDirectory(p, T, T, 1, false, Optional.empty(), hidden));
    }

    private static Arbitrary<SessionSpec> sessionSpecs() {
        Arbitrary<Integer> number = Arbitraries.integers().between(1, 4);
        Arbitrary<SessionSpec> wsl =
                Combinators.combine(maybe(wslPaths()), number).as((cwd, n) -> new SessionSpec(WSL, cwd, n));
        Arbitrary<SessionSpec> win =
                Combinators.combine(maybe(winPaths()), number).as((cwd, n) -> new SessionSpec(WIN, cwd, n));
        return Arbitraries.oneOf(wsl, win);
    }

    // cwd 를 아는 세션과 모르는 세션(홈에서 띄워 OSC 가 오기 전)을 섞는다.
    private static Arbitrary<Optional<WorkspacePath>> maybe(Arbitrary<WorkspacePath> paths) {
        return Arbitraries.oneOf(paths.map(Optional::of), Arbitraries.just(Optional.<WorkspacePath>empty()));
    }

    @Provide
    Arbitrary<Input> inputs() {
        return Combinators.combine(
                        entries().list().ofMaxSize(12), sessionSpecs().list().ofMaxSize(5))
                .as((entries, specs) -> new Input(uniqueByKey(entries), sessions(specs)));
    }

    // 대소문자만 다른 Windows 경로는 같은 키다. 먼저 나온 것만 남겨 restore 가 무엇도 합치지 않게 한다.
    private static List<VisitedDirectory> uniqueByKey(List<VisitedDirectory> entries) {
        Map<WorkspacePath, VisitedDirectory> unique = new LinkedHashMap<>();
        for (VisitedDirectory v : entries) {
            unique.putIfAbsent(v.path(), v);
        }
        return List.copyOf(unique.values());
    }

    // 창 ID 는 세션마다 다르다. 번호는 겹칠 수 있다.
    private static List<SessionLeaf> sessions(List<SessionSpec> specs) {
        List<SessionLeaf> out = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            SessionSpec s = specs.get(i);
            out.add(new SessionLeaf(
                    new PaneId("p" + i), s.number(), "sh", s.env(), s.cwd(), new SessionStatus.Running()));
        }
        return out;
    }

    private static HistoryTree project(Input input, boolean showHidden) {
        WorkHistory h = WorkHistory.restore(input.entries(), HistoryPolicy.DEFAULT);
        return DirectoryTree.project(h, input.sessions(), new TreeQuery("", showHidden, List.of(WSL, WIN)));
    }

    private static void collect(List<TreeNode> nodes, List<TreeNode.Folder> out) {
        for (TreeNode n : nodes) {
            if (n instanceof TreeNode.Folder f) {
                out.add(f);
                collect(f.children(), out);
            }
        }
    }

    private static List<TreeNode.Folder> folders(HistoryTree tree) {
        List<TreeNode.Folder> out = new ArrayList<>();
        for (EnvironmentNode e : tree.environments()) {
            collect(e.children(), out);
        }
        return out;
    }

    private static boolean hasSessionChild(TreeNode.Folder f) {
        return f.children().stream().anyMatch(c -> c instanceof TreeNode.Session);
    }

    @Property
    @Label("보이는 방문 경로는 모두 트리에 방문 노드로 정확히 한 번 나타난다")
    void everyVisitAppearsExactlyOnce(@ForAll("inputs") Input input, @ForAll boolean showHidden) {
        // when
        List<WorkspacePath> shown = folders(project(input, showHidden)).stream()
                .filter(f -> f.visit().isPresent())
                .map(f -> f.visit().orElseThrow().path())
                .toList();

        // then
        List<WorkspacePath> expected = input.entries().stream()
                .filter(v -> showHidden || !v.hidden())
                .map(VisitedDirectory::path)
                .toList();
        assertThat(shown).hasSize(expected.size()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Property
    @Label("압축 뒤 방문도 세션도 없는 중간 폴더는 자식이 둘 이상이거나 루트다")
    void unvisitedInnerNodesBranchOrAreRoots(@ForAll("inputs") Input input) {
        // when
        List<TreeNode.Folder> all = folders(project(input, false));

        // then
        for (TreeNode.Folder f : all) {
            boolean isRoot = f.path().parent().isEmpty();
            if (f.visit().isEmpty() && !hasSessionChild(f) && !isRoot) {
                assertThat(f.children()).as("압축되지 않은 중간 노드 %s", f.label()).hasSizeGreaterThan(1);
            }
            if (f.visit().isEmpty() && isRoot) {
                assertThat(f.children()).isNotEmpty();
            }
        }
    }

    @Property
    @Label("모든 폴더의 자식 경로는 그 폴더 경로 아래에 있고 라벨은 비지 않는다")
    void childrenLieUnderParent(@ForAll("inputs") Input input) {
        // when
        List<TreeNode.Folder> all = folders(project(input, true));

        // then
        for (TreeNode.Folder f : all) {
            assertThat(f.label()).isNotBlank();
            for (TreeNode c : f.children()) {
                if (c instanceof TreeNode.Folder child) {
                    assertThat(isUnder(child.path(), f.path())).isTrue();
                }
            }
        }
    }

    @Property
    @Label("라벨은 path().path() 의 끝부분과 글자까지 같다")
    void labelIsExactSuffixOfPath(@ForAll("inputs") Input input, @ForAll boolean showHidden) {
        // when
        List<TreeNode.Folder> all = folders(project(input, showHidden));

        // then
        for (TreeNode.Folder f : all) {
            assertThat(f.path().path()).as("라벨 %s", f.label()).endsWith(f.label());
        }
    }

    @Property
    @Label("모든 세션은 정확히 한 번, 자기 cwd 폴더 아래나(cwd 가 없으면) 자기 환경 노드 바로 아래에 있다")
    void everySessionHangsOnceUnderItsPlace(@ForAll("inputs") Input input, @ForAll boolean showHidden) {
        // given
        HistoryTree tree = project(input, showHidden);
        Map<SessionLeaf, List<Object>> parents = new HashMap<>();

        // when
        for (EnvironmentNode e : tree.environments()) {
            collectSessionParents(e, e.children(), parents);
        }

        // then
        assertThat(parents.keySet()).containsExactlyInAnyOrderElementsOf(input.sessions());
        for (SessionLeaf s : input.sessions()) {
            List<Object> where = parents.get(s);
            assertThat(where).as("세션 %s 의 부모", s.pane()).hasSize(1);
            Object parent = where.get(0);
            if (s.cwd().isPresent()) {
                assertThat(parent)
                        .isInstanceOfSatisfying(
                                TreeNode.Folder.class,
                                f -> assertThat(f.path()).isEqualTo(s.cwd().get()));
            } else {
                assertThat(parent)
                        .isInstanceOfSatisfying(
                                EnvironmentNode.class, e -> assertThat(e.env()).isEqualTo(s.env()));
            }
        }
    }

    private static void collectSessionParents(Object parent, List<TreeNode> nodes, Map<SessionLeaf, List<Object>> out) {
        for (TreeNode n : nodes) {
            switch (n) {
                case TreeNode.Session s ->
                    out.computeIfAbsent(s.session(), k -> new ArrayList<>()).add(parent);
                case TreeNode.Folder f -> collectSessionParents(f, f.children(), out);
            }
        }
    }

    @Property
    @Label("대소문자 중복 없는 같은 입력의 투영은 항목 · 세션 순서와 상관없이 표기까지 같은 트리다")
    void projectionIgnoresInputOrder(@ForAll("inputs") Input input, @ForAll boolean showHidden, @ForAll long seed) {
        // given
        Random random = new Random(seed);
        List<VisitedDirectory> entries = new ArrayList<>(input.entries());
        Collections.shuffle(entries, random);
        List<SessionLeaf> sessions = new ArrayList<>(input.sessions());
        Collections.shuffle(sessions, random);

        // when
        String original = shape(project(input, showHidden));
        String shuffled = shape(project(new Input(entries, sessions), showHidden));

        // then
        assertThat(shuffled).isEqualTo(original);
    }

    // 환경 → 자식 순서 · 라벨 · 경로 표기 · 방문 여부 · 세션 창 ID 를 그대로(대소문자 보존) 펼친다.
    private static String shape(HistoryTree tree) {
        StringBuilder sb = new StringBuilder();
        for (EnvironmentNode e : tree.environments()) {
            sb.append(e.env()).append('\n');
            shape(e.children(), 1, sb);
        }
        return sb.toString();
    }

    private static void shape(List<TreeNode> nodes, int depth, StringBuilder sb) {
        for (TreeNode n : nodes) {
            sb.append("  ".repeat(depth));
            switch (n) {
                case TreeNode.Session s ->
                    sb.append('#').append(s.session().pane().value()).append('\n');
                case TreeNode.Folder f -> {
                    sb.append(f.label())
                            .append('|')
                            .append(f.path().path())
                            .append('|')
                            .append(f.visit().isPresent())
                            .append('\n');
                    shape(f.children(), depth + 1, sb);
                }
            }
        }
    }

    private static boolean isUnder(WorkspacePath child, WorkspacePath ancestor) {
        Optional<WorkspacePath> p = child.parent();
        while (p.isPresent()) {
            if (p.get().equals(ancestor)) {
                return true;
            }
            p = p.get().parent();
        }
        return false;
    }
}
