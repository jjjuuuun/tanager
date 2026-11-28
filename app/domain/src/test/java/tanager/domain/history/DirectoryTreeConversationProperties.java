package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/** 트리 투영의 대화 불변식(#214). 검색 없이 숨긴 항목까지 모두 보이면 붙을 수 있는 대화는 하나도 빠지거나 겹치지 않는다. */
class DirectoryTreeConversationProperties {

    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("U");
    private static final Instant T = Instant.parse("2026-11-01T00:00:00Z");

    /** 투영 입력 — 대소문자 중복 없는 이력 항목과 대화. */
    record Input(List<VisitedDirectory> entries, List<PastConversation> conversations) {}

    private record ConversationSpec(WorkspacePath cwd, int at) {}

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    // 대소문자만 다른 경로를 섞어 Windows 는 붙고 WSL 은 붙지 않는 경우를 고르게 만든다.
    private static Arbitrary<WorkspacePath> paths() {
        Arbitrary<List<String>> segments =
                Arbitraries.of("a", "A", "b", "src", "proj").list().ofMaxSize(3);
        Arbitrary<WorkspacePath> wsl = segments.map(l -> path(WSL, "/" + String.join("/", l)));
        Arbitrary<WorkspacePath> win = segments.flatMap(l ->
                Arbitraries.of("C:\\", "c:\\").map(root -> path(WIN, root + String.join("\\", l))));
        return Arbitraries.oneOf(wsl, win);
    }

    private static Arbitrary<VisitedDirectory> entries() {
        return Combinators.combine(paths(), Arbitraries.of(false, true))
                .as((p, hidden) -> new VisitedDirectory(p, T, T, 1, false, Optional.empty(), hidden));
    }

    private static Arbitrary<ConversationSpec> specs() {
        return Combinators.combine(paths(), Arbitraries.integers().between(0, 3))
                .as(ConversationSpec::new);
    }

    @Provide
    Arbitrary<Input> inputs() {
        return Combinators.combine(entries().list().ofMaxSize(8), specs().list().ofMaxSize(20))
                .as((entries, specs) -> new Input(uniqueByKey(entries), conversations(specs)));
    }

    private static List<VisitedDirectory> uniqueByKey(List<VisitedDirectory> entries) {
        Map<WorkspacePath, VisitedDirectory> unique = new LinkedHashMap<>();
        for (VisitedDirectory v : entries) {
            unique.putIfAbsent(v.path(), v);
        }
        return List.copyOf(unique.values());
    }

    // ID 는 대화마다 다르다. 시각은 겹칠 수 있다.
    private static List<PastConversation> conversations(List<ConversationSpec> specs) {
        List<PastConversation> out = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            ConversationSpec s = specs.get(i);
            out.add(new PastConversation(
                    VisitSource.CLAUDE_CODE,
                    new ConversationId("c" + i),
                    s.cwd(),
                    Optional.of("대화 " + i),
                    T.plusSeconds(s.at())));
        }
        return out;
    }

    private static HistoryTree project(Input input, int perFolder, Set<WorkspacePath> expanded) {
        WorkHistory h = WorkHistory.restore(input.entries(), HistoryPolicy.DEFAULT);
        return DirectoryTree.project(
                h, List.of(), input.conversations(), new TreeQuery("", true, List.of(WSL, WIN), perFolder, expanded));
    }

    private static long attachable(Input input) {
        Set<WorkspacePath> visited = new HashSet<>();
        for (VisitedDirectory v : input.entries()) {
            visited.add(v.path());
        }
        return input.conversations().stream()
                .filter(c -> visited.contains(c.cwd()))
                .count();
    }

    /** 폴더마다 (그 폴더, 대화 자식들). */
    private static void collect(List<TreeNode> nodes, Map<TreeNode.Folder, List<TreeNode>> out) {
        for (TreeNode n : nodes) {
            if (n instanceof TreeNode.Folder f) {
                List<TreeNode> mine = new ArrayList<>();
                for (TreeNode c : f.children()) {
                    if (c instanceof TreeNode.Conversation || c instanceof TreeNode.MoreConversations) {
                        mine.add(c);
                    }
                }
                out.put(f, mine);
                collect(f.children(), out);
            }
        }
    }

    private static Map<TreeNode.Folder, List<TreeNode>> conversationsByFolder(HistoryTree tree) {
        Map<TreeNode.Folder, List<TreeNode>> out = new LinkedHashMap<>();
        for (EnvironmentNode e : tree.environments()) {
            for (TreeNode n : e.children()) {
                assertThat(n).isNotInstanceOfAny(TreeNode.Conversation.class, TreeNode.MoreConversations.class);
            }
            collect(e.children(), out);
        }
        return out;
    }

    private static long shownPlusHidden(HistoryTree tree) {
        long total = 0;
        for (List<TreeNode> nodes : conversationsByFolder(tree).values()) {
            for (TreeNode n : nodes) {
                total += switch (n) {
                    case TreeNode.MoreConversations m -> m.hidden();
                    case TreeNode.Conversation _ -> 1;
                    case TreeNode.Folder _, TreeNode.Session _ -> 0;
                };
            }
        }
        return total;
    }

    @Property
    @Label("보이는 대화 수 + 더 보기의 숨긴 수 = 방문 폴더에 붙을 수 있는 대화 수(검색 없음 · 모두 보임)")
    void everyAttachableConversationIsCounted(
            @ForAll("inputs") Input input, @ForAll @IntRange(min = 1, max = 6) int perFolder) {
        // when
        HistoryTree tree = project(input, perFolder, Set.of());

        // then
        assertThat(shownPlusHidden(tree)).isEqualTo(attachable(input));
    }

    @Property
    @Label("방문 폴더를 모두 펼치면 붙을 수 있는 대화가 모두 보이고 더 보기가 없다")
    void expandedShowsEveryConversation(
            @ForAll("inputs") Input input, @ForAll @IntRange(min = 1, max = 3) int perFolder) {
        // given
        Set<WorkspacePath> all = new HashSet<>();
        for (VisitedDirectory v : input.entries()) {
            all.add(v.path());
        }

        // when
        HistoryTree tree = project(input, perFolder, all);

        // then
        long shown = 0;
        for (List<TreeNode> nodes : conversationsByFolder(tree).values()) {
            assertThat(nodes).noneMatch(TreeNode.MoreConversations.class::isInstance);
            shown += nodes.size();
        }
        assertThat(shown).isEqualTo(attachable(input));
    }

    @Property
    @Label("폴더의 대화는 그 폴더 경로의 것이고 자식 끝에 최근 순으로, 보일 수 이하, 더 보기는 맨 끝 하나다")
    void conversationsAreLastSortedAndBounded(
            @ForAll("inputs") Input input, @ForAll @IntRange(min = 1, max = 6) int perFolder) {
        // when
        HistoryTree tree = project(input, perFolder, Set.of());

        // then
        Comparator<PastConversation> newestFirst =
                Comparator.comparing(PastConversation::lastActive).reversed().thenComparing(c -> c.id().value());
        for (Map.Entry<TreeNode.Folder, List<TreeNode>> e :
                conversationsByFolder(tree).entrySet()) {
            TreeNode.Folder folder = e.getKey();
            List<TreeNode> nodes = e.getValue();
            List<TreeNode> children = folder.children();
            assertThat(children.subList(children.size() - nodes.size(), children.size()))
                    .as("대화는 자식 끝에 모인다")
                    .isEqualTo(nodes);
            List<PastConversation> shown = new ArrayList<>();
            for (int i = 0; i < nodes.size(); i++) {
                switch (nodes.get(i)) {
                    case TreeNode.Conversation c -> {
                        assertThat(c.conversation().cwd()).isEqualTo(folder.path());
                        shown.add(c.conversation());
                    }
                    case TreeNode.MoreConversations m -> {
                        assertThat(i).as("더 보기는 맨 끝").isEqualTo(nodes.size() - 1);
                        assertThat(m.folder()).isEqualTo(folder.path());
                        assertThat(shown).hasSize(perFolder);
                    }
                    case TreeNode.Folder _, TreeNode.Session _ -> throw new AssertionError(nodes.get(i));
                }
            }
            assertThat(shown).hasSizeLessThanOrEqualTo(perFolder).isSortedAccordingTo(newestFirst);
            if (!shown.isEmpty()) {
                assertThat(folder.visit()).isPresent();
            }
        }
    }
}
