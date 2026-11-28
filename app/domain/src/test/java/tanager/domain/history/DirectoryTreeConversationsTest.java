package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static tanager.domain.history.DirectoryTreeTest.env;
import static tanager.domain.history.DirectoryTreeTest.folder;
import static tanager.domain.history.DirectoryTreeTest.history;
import static tanager.domain.history.DirectoryTreeTest.path;
import static tanager.domain.history.DirectoryTreeTest.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** 트리 투영의 이전 대화(FR-TK-09, #214). 대화는 cwd 와 같은 경로의 방문 폴더 아래, 세션 · 하위 폴더 뒤에 최근 순으로 붙는다. */
class DirectoryTreeConversationsTest {

    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment WSL_U = new ShellEnvironment.Wsl("U");
    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    private static final WorkspacePath A = path(WSL_U, "/home/me/a");

    private static PastConversation conversation(String id, WorkspacePath cwd, int at) {
        return conversation(id, cwd, at, "대화 " + id);
    }

    private static PastConversation conversation(String id, WorkspacePath cwd, int at, String title) {
        return new PastConversation(
                VisitSource.CLAUDE_CODE,
                new ConversationId(id),
                cwd,
                PastConversation.summarize(title),
                T0.plusSeconds(at));
    }

    private static TreeQuery query(String text, boolean showHidden, int perFolder, Set<WorkspacePath> expanded) {
        return new TreeQuery(text, showHidden, List.of(WSL_U, WIN), perFolder, expanded);
    }

    private static final TreeQuery ALL = query("", false, 5, Set.of());

    private static HistoryTree project(WorkHistory h, List<PastConversation> conversations, TreeQuery q) {
        return DirectoryTree.project(h, List.of(), conversations, q);
    }

    // 폴더는 라벨, 세션은 #번호, 대화는 ID, 더 보기는 +숨긴 수.
    private static List<String> labels(List<TreeNode> nodes) {
        return nodes.stream()
                .map(n -> switch (n) {
                    case TreeNode.Folder f -> f.label();
                    case TreeNode.Session s -> "#" + s.session().number();
                    case TreeNode.Conversation c -> c.conversation().id().value();
                    case TreeNode.MoreConversations m -> "+" + m.hidden();
                })
                .toList();
    }

    private static List<TreeNode> rootChildren(HistoryTree tree, ShellEnvironment env) {
        return folder(env(tree, env).children().getFirst()).children();
    }

    private static List<PastConversation> seven(WorkspacePath cwd) {
        List<PastConversation> out = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            out.add(conversation("c" + i, cwd, i));
        }
        return out;
    }

    private static int conversationCount(List<TreeNode> nodes) {
        int count = 0;
        for (TreeNode n : nodes) {
            switch (n) {
                case TreeNode.Folder f -> count += conversationCount(f.children());
                case TreeNode.Conversation _, TreeNode.MoreConversations _ -> count++;
                case TreeNode.Session _ -> {}
            }
        }
        return count;
    }

    private static int conversationCount(HistoryTree tree) {
        int count = 0;
        for (EnvironmentNode e : tree.environments()) {
            count += conversationCount(e.children());
        }
        return count;
    }

    @Nested
    class 순서 {

        @Test
        @DisplayName("방문 폴더의 대화는 그 폴더의 자식 끝에 최근 순으로 붙는다")
        void conversationsNewestFirst() {
            // given
            List<PastConversation> conversations = List.of(conversation("c1", A, 3), conversation("c2", A, 5));

            // when
            HistoryTree tree = project(history(A), conversations, ALL);

            // then
            TreeNode.Folder a = folder(env(tree, WSL_U).children().getFirst());
            assertThat(a.path()).isEqualTo(A);
            assertThat(labels(a.children())).containsExactly("c2", "c1");
            assertThat(a.children().getFirst()).isEqualTo(new TreeNode.Conversation(conversation("c2", A, 5)));
        }

        @Test
        @DisplayName("폴더의 자식은 세션 → 하위 폴더 → 대화 순이다 — 세션 · 폴더 순서는 지금과 같다")
        void sessionsThenFoldersThenConversations() {
            // given
            WorkspacePath b = path(WSL_U, "/home/me/a/b");
            List<PastConversation> conversations = List.of(conversation("c1", A, 3), conversation("c2", A, 5));

            // when
            HistoryTree tree = DirectoryTree.project(history(A, b), List.of(session(1, A)), conversations, ALL);

            // then
            TreeNode.Folder a = folder(env(tree, WSL_U).children().getFirst());
            assertThat(labels(a.children())).containsExactly("#1", "b", "c2", "c1");
        }

        @Test
        @DisplayName("마지막 활동 시각이 같으면 ID 순이다")
        void sameTimeOrdersById() {
            // given
            List<PastConversation> conversations =
                    List.of(conversation("zeta", A, 1), conversation("alpha", A, 1), conversation("mid", A, 2));

            // when
            HistoryTree tree = project(history(A), conversations, ALL);

            // then
            assertThat(labels(rootChildren(tree, WSL_U))).containsExactly("mid", "alpha", "zeta");
        }
    }

    @Nested
    class 보일_수 {

        @Test
        @DisplayName("보일 수를 넘은 대화는 더 보기 하나로 묶인다")
        void overflowBecomesMore() {
            // when
            HistoryTree tree = project(history(A), seven(A), ALL);

            // then
            List<TreeNode> children = rootChildren(tree, WSL_U);
            assertThat(labels(children)).containsExactly("c7", "c6", "c5", "c4", "c3", "+2");
            assertThat(children.getLast()).isEqualTo(new TreeNode.MoreConversations(A, 2));
        }

        @Test
        @DisplayName("펼친 목록에 있는 폴더는 대화가 모두 보이고 더 보기가 없다")
        void expandedShowsAll() {
            // when
            HistoryTree tree = project(history(A), seven(A), query("", false, 5, Set.of(A)));

            // then
            assertThat(labels(rootChildren(tree, WSL_U))).containsExactly("c7", "c6", "c5", "c4", "c3", "c2", "c1");
        }

        @Test
        @DisplayName("보일 수가 정확히 대화 수면 더 보기가 없다")
        void exactlyLimitHasNoMore() {
            // when
            HistoryTree tree = project(history(A), seven(A), query("", false, 7, Set.of()));

            // then
            assertThat(labels(rootChildren(tree, WSL_U))).hasSize(7).doesNotContain("+0");
        }

        @Test
        @DisplayName("보일 수가 0 이면 펼쳤어도 대화 · 더 보기 노드가 없다")
        void zeroHidesConversations() {
            // when
            HistoryTree tree = project(history(A), seven(A), query("", false, 0, Set.of(A)));

            // then
            assertThat(rootChildren(tree, WSL_U)).isEmpty();
        }

        @Test
        @DisplayName("기본 보일 수는 5 이고 펼친 폴더는 없다")
        void defaultQuery() {
            // when
            TreeQuery q = new TreeQuery("", false, List.of(WSL_U));

            // then
            assertThat(q.conversationsPerFolder()).isEqualTo(TreeQuery.DEFAULT_CONVERSATIONS_PER_FOLDER);
            assertThat(TreeQuery.DEFAULT_CONVERSATIONS_PER_FOLDER).isEqualTo(5);
            assertThat(q.expandedConversations()).isEmpty();
        }
    }

    @Nested
    class 붙는_폴더 {

        @Test
        @DisplayName("Windows 는 대소문자만 다른 cwd 의 대화도 방문 폴더에 붙는다 — 펼친 목록도 대소문자를 무시한다")
        void windowsIgnoresCase() {
            // given
            WorkspacePath visited = path(WIN, "c:\\work\\app");
            WorkspacePath cwd = path(WIN, "C:\\Work\\App");
            List<PastConversation> conversations = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                conversations.add(conversation("w" + i, cwd, i));
            }

            // when
            HistoryTree folded = project(history(visited), conversations, ALL);
            HistoryTree opened = project(history(visited), conversations, query("", false, 5, Set.of(cwd)));

            // then
            assertThat(labels(rootChildren(folded, WIN))).containsExactly("w6", "w5", "w4", "w3", "w2", "+1");
            assertThat(labels(rootChildren(opened, WIN))).hasSize(6).doesNotContain("+1");
        }

        @Test
        @DisplayName("WSL 은 대소문자가 다른 cwd 의 대화를 붙이지 않는다")
        void wslIsCaseSensitive() {
            // given
            WorkspacePath visited = path(WSL_U, "/home/me");
            List<PastConversation> conversations = List.of(conversation("c1", path(WSL_U, "/home/Me"), 1));

            // when
            HistoryTree tree = project(history(visited), conversations, ALL);

            // then
            assertThat(tree).isEqualTo(project(history(visited), List.of(), ALL));
        }

        @Test
        @DisplayName("cwd 에 방문 폴더가 없으면 대화는 어디에도 없고 새 폴더 노드도 생기지 않는다 — 방문 없는 중간 폴더 · 세션 폴더 포함")
        void noVisitedFolderNoConversation() {
            // given: /home/me 는 방문 없는 중간 폴더, /srv 는 세션만 있는 폴더, /opt 는 아무것도 아니다
            WorkspacePath b = path(WSL_U, "/home/me/b");
            WorkHistory h = history(A, b);
            List<SessionLeaf> sessions = List.of(session(1, path(WSL_U, "/srv")));
            List<PastConversation> conversations = List.of(
                    conversation("mid", path(WSL_U, "/home/me"), 1),
                    conversation("srv", path(WSL_U, "/srv"), 2),
                    conversation("opt", path(WSL_U, "/opt/x"), 3));

            // when
            HistoryTree tree = DirectoryTree.project(h, sessions, conversations, ALL);

            // then
            assertThat(tree).isEqualTo(DirectoryTree.project(h, sessions, ALL));
            assertThat(conversationCount(tree)).isZero();
        }

        @Test
        @DisplayName("숨긴 폴더의 대화는 showHidden 이 아니면 없고, showHidden 이면 보인다")
        void hiddenFolder() {
            // given
            WorkHistory h = history(A).hide(A, true).value().orElseThrow();
            List<PastConversation> conversations = List.of(conversation("c1", A, 1));

            // when
            HistoryTree hidden = project(h, conversations, ALL);
            HistoryTree shown = project(h, conversations, query("", true, 5, Set.of()));

            // then
            assertThat(conversationCount(hidden)).isZero();
            assertThat(labels(rootChildren(shown, WSL_U))).containsExactly("c1");
        }

        @Test
        @DisplayName("대화가 붙은 방문 폴더와 그 위의 압축은 대화가 없을 때와 같다")
        void compressionUnchanged() {
            // given: /home/me/a 하나만 방문 — 환경 아래 한 줄
            List<PastConversation> conversations = List.of(conversation("c1", A, 1));

            // when
            HistoryTree tree = project(history(A), conversations, ALL);

            // then
            TreeNode.Folder a = folder(env(tree, WSL_U).children().getFirst());
            assertThat(a.label()).isEqualTo("/home/me/a");
            assertThat(labels(a.children())).containsExactly("c1");
        }

        @Test
        @DisplayName("사라진 경로의 회색 폴더 아래에도 대화가 그대로 보인다")
        void missingFolderKeepsConversations() {
            // given
            List<PastConversation> conversations = List.of(conversation("c1", A, 1));

            // when
            HistoryTree tree = DirectoryTree.project(history(A), List.of(), conversations, ALL, Set.of(A));

            // then
            TreeNode.Folder a = folder(env(tree, WSL_U).children().getFirst());
            assertThat(a.missing()).isTrue();
            assertThat(labels(a.children())).containsExactly("c1");
        }

        @Test
        @DisplayName("대화 없이 부르는 기존 투영은 빈 대화 목록으로 부른 것과 같다")
        void legacyOverloadsMatchEmptyConversations() {
            // given
            WorkHistory h = history(A, path(WSL_U, "/home/me/b"));
            List<SessionLeaf> sessions = List.of(session(1, A));

            // when · then
            assertThat(DirectoryTree.project(h, sessions, ALL))
                    .isEqualTo(DirectoryTree.project(h, sessions, List.of(), ALL));
            assertThat(DirectoryTree.project(h, sessions, ALL, Set.of(A)))
                    .isEqualTo(DirectoryTree.project(h, sessions, List.of(), ALL, Set.of(A)));
        }
    }

    @Nested
    class 검색 {

        @Test
        @DisplayName("검색어가 대화 요약에만 맞으면 그 대화와 조상 폴더만 보인다 — 폴더 자체는 맞은 것이 아니다")
        void titleMatchKeepsConversationAndAncestors() {
            // given
            WorkspacePath b = path(WSL_U, "/home/me/b");
            List<PastConversation> conversations = List.of(
                    conversation("c1", A, 1, "로그인 화면 고치기"),
                    conversation("c2", A, 2, "빌드 캐시"),
                    conversation("c3", b, 3, "문서 정리"));

            // when
            HistoryTree tree = project(history(A, b), conversations, query("로그인", false, 5, Set.of()));

            // then
            List<TreeNode> roots = env(tree, WSL_U).children();
            assertThat(roots).hasSize(1);
            TreeNode.Folder a = folder(roots.getFirst());
            assertThat(a.path()).isEqualTo(A);
            assertThat(a.matched()).isFalse();
            assertThat(labels(a.children())).containsExactly("c1");
        }

        @Test
        @DisplayName("요약 검색은 대소문자를 가리지 않고 앞뒤 공백을 무시한다")
        void titleMatchIgnoresCase() {
            // given
            List<PastConversation> conversations =
                    List.of(conversation("c1", A, 1, "Fix LOGIN flow"), conversation("c2", A, 2, "other"));

            // when
            HistoryTree tree = project(history(A), conversations, query("  login ", false, 5, Set.of()));

            // then
            assertThat(labels(rootChildren(tree, WSL_U))).containsExactly("c1");
        }

        @Test
        @DisplayName("폴더가 검색어에 맞으면 그 폴더의 대화는 평소 규칙대로 보인다")
        void matchedFolderShowsConversationsAsUsual() {
            // when
            HistoryTree tree = project(history(A), seven(A), query("a", false, 5, Set.of()));

            // then
            TreeNode.Folder a = folder(env(tree, WSL_U).children().getFirst());
            assertThat(a.matched()).isTrue();
            assertThat(labels(a.children())).containsExactly("c7", "c6", "c5", "c4", "c3", "+2");
        }

        @Test
        @DisplayName("요약에 맞은 대화에도 보일 수와 더 보기 규칙이 같다")
        void titleMatchesRespectLimit() {
            // given
            List<PastConversation> conversations = new ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                conversations.add(conversation("m" + i, A, i, "로그인 " + i));
            }
            conversations.add(conversation("x", A, 99, "다른 것"));

            // when
            HistoryTree tree = project(history(A), conversations, query("로그인", false, 5, Set.of()));

            // then
            assertThat(labels(rootChildren(tree, WSL_U))).containsExactly("m7", "m6", "m5", "m4", "m3", "+2");
        }

        @Test
        @DisplayName("요약에 맞은 대화라도 숨긴 폴더 · 방문 없는 cwd 면 보이지 않는다")
        void titleMatchNeedsVisibleVisit() {
            // given
            WorkHistory h = history(A).hide(A, true).value().orElseThrow();
            List<PastConversation> conversations =
                    List.of(conversation("c1", A, 1, "로그인"), conversation("c2", path(WSL_U, "/opt"), 2, "로그인"));

            // when
            HistoryTree tree = project(h, conversations, query("로그인", false, 5, Set.of()));

            // then
            assertThat(conversationCount(tree)).isZero();
            assertThat(env(tree, WSL_U).children()).isEmpty();
        }

        @Test
        @DisplayName("요약에 맞은 대화가 있는 폴더에도 그 폴더의 세션이 매달린다")
        void titleMatchKeepsSessions() {
            // given
            List<PastConversation> conversations = List.of(conversation("c1", A, 1, "로그인"));

            // when
            HistoryTree tree = DirectoryTree.project(
                    history(A), List.of(session(1, A)), conversations, query("로그인", false, 5, Set.of()));

            // then
            assertThat(labels(rootChildren(tree, WSL_U))).containsExactly("#1", "c1");
        }
    }

    @Nested
    class 고정됨 {

        @Test
        @DisplayName("고정된 폴더의 대화는 폴더 트리에만 붙고, 대화 요약에만 맞는 검색어로는 고정 섹션에 나오지 않는다")
        void pinnedSectionHasNoConversations() {
            // given
            WorkHistory h = history(A).pin(A, true).value().orElseThrow();
            List<PastConversation> conversations = List.of(conversation("c1", A, 1, "로그인"));

            // when
            HistoryTree tree = project(h, conversations, ALL);
            HistoryTree searched = project(h, conversations, query("로그인", false, 5, Set.of()));

            // then
            assertThat(tree.pinned()).extracting(p -> p.entry().path()).containsExactly(A);
            assertThat(labels(rootChildren(tree, WSL_U))).containsExactly("c1");
            assertThat(searched.pinned()).isEmpty();
            assertThat(labels(rootChildren(searched, WSL_U))).containsExactly("c1");
        }
    }
}
