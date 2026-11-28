package tanager.ui.sidebar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ConversationId;
import tanager.domain.history.DirectoryTree;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.HistoryTree;
import tanager.domain.history.PastConversation;
import tanager.domain.history.SessionLeaf;
import tanager.domain.history.SessionStatus;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.TreeQuery;
import tanager.domain.history.VisitSource;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.PaneId;

/** 트리가 다시 투영돼도 펼침 · 선택을 경로 · 창 ID 로 이어 간다(#84). */
class ExpansionStateTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final Instant T0 = Instant.parse("2026-11-27T00:00:00Z");
    private static final NodeKey ENV = new NodeKey.Environment(UBUNTU);

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private static NodeKey folder(String raw) {
        return new NodeKey.Folder(wsl(raw));
    }

    private static HistoryTree tree(List<SessionLeaf> sessions, String... visited) {
        WorkHistory history = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (String raw : visited) {
            history = history.recordVisit(wsl(raw), T0);
        }
        return DirectoryTree.project(history, sessions, new TreeQuery("", false, List.of(UBUNTU)));
    }

    private static HistoryTree tree(String... visited) {
        return tree(List.of(), visited);
    }

    private static SessionLeaf session(String pane, String cwd) {
        return new SessionLeaf(new PaneId(pane), 1, "bash", UBUNTU, Optional.of(wsl(cwd)), new SessionStatus.Running());
    }

    @Test
    @DisplayName("새 방문으로 트리가 바뀌어도 펼친 폴더와 선택이 그대로다")
    void keepsExpansionAndSelectionAcrossNewVisit() {
        // given: /mnt/c/Users/me/Desktop 아래 두 폴더를 방문했고 Desktop 을 펼쳐 골랐다
        HistoryTree before = tree("/mnt/c/Users/me/Desktop/a", "/mnt/c/Users/me/Desktop/b");
        ExpansionState state = new ExpansionState(
                Set.of(ENV, folder("/mnt/c/Users/me/Desktop")), Optional.of(folder("/mnt/c/Users/me/Desktop")));
        assertThat(Nodes.keys(before)).contains(folder("/mnt/c/Users/me/Desktop"));

        // when: 형제 폴더가 하나 더 생긴다
        ExpansionState.Restored restored = state.restoreIn(
                tree("/mnt/c/Users/me/Desktop/a", "/mnt/c/Users/me/Desktop/b", "/mnt/c/Users/me/Desktop/c"));

        // then
        assertThat(restored.expanded()).contains(ENV, folder("/mnt/c/Users/me/Desktop"));
        assertThat(restored.selected()).contains(folder("/mnt/c/Users/me/Desktop"));
    }

    @Test
    @DisplayName("압축 노드가 방문으로 쪼개지면 펼쳐 있던 노드는 새 부모 아래에서 펼친 채 보인다")
    void splitCompressedNodeStaysExpandedAndVisible() {
        // given: /a/b/c 만 방문해 한 줄로 압축돼 있고 그것을 펼쳤다
        HistoryTree before = tree("/a/b/c/x", "/a/b/c/y");
        assertThat(Nodes.keys(before)).contains(folder("/a/b/c")).doesNotContain(folder("/a"));
        ExpansionState state = new ExpansionState(Set.of(ENV, folder("/a/b/c")), Optional.empty());

        // when: /a 를 방문해 /a 가 따로 서고 b/c 가 그 아래로 간다
        ExpansionState.Restored restored = state.restoreIn(tree("/a", "/a/b/c/x", "/a/b/c/y"));

        // then: /a/b/c 는 펼친 채이고, 보이도록 새 부모 /a 도 펼친다
        assertThat(restored.expanded()).contains(ENV, folder("/a"), folder("/a/b/c"));
    }

    @Test
    @DisplayName("따로 있던 폴더가 압축으로 합쳐지면 그 압축 노드가 펼침 · 선택을 이어받는다")
    void mergedFolderPassesStateToCompressedNode() {
        // given: /a 와 /a/b 가 따로 있고(/a 방문) /a 를 펼쳐 골랐다
        ExpansionState state = new ExpansionState(Set.of(ENV, folder("/a")), Optional.of(folder("/a")));

        // when: /a 방문이 없어져 /a/b 로 압축된다
        HistoryTree merged = tree("/a/b/x", "/a/b/y");
        assertThat(Nodes.keys(merged)).contains(folder("/a/b")).doesNotContain(folder("/a"));
        ExpansionState.Restored restored = state.restoreIn(merged);

        // then
        assertThat(restored.expanded()).contains(folder("/a/b"));
        assertThat(restored.selected()).contains(folder("/a/b"));
    }

    @Test
    @DisplayName("고른 세션은 셸이 다른 폴더로 옮겨도 창 ID 로 다시 고르고, 보이도록 조상을 펼친다")
    void selectedSessionFollowsPaneId() {
        // given
        NodeKey p1 = new NodeKey.Session(new PaneId("p1"));
        ExpansionState state = new ExpansionState(Set.of(ENV), Optional.of(p1));

        // when: 세션이 /srv/app 으로 옮겼다
        ExpansionState.Restored restored =
                state.restoreIn(tree(List.of(session("p1", "/srv/app")), "/home/me", "/srv/app"));

        // then
        assertThat(restored.selected()).contains(p1);
        assertThat(restored.expanded()).contains(ENV, folder("/srv/app"));
    }

    @Test
    @DisplayName("고른 노드가 사라지면 선택이 없다")
    void vanishedSelectionIsEmpty() {
        // given
        ExpansionState state = new ExpansionState(Set.of(ENV), Optional.of(folder("/gone")));

        // when
        ExpansionState.Restored restored = state.restoreIn(tree("/home/me"));

        // then
        assertThat(restored.selected()).isEmpty();
    }

    @Test
    @DisplayName("펼쳤던 기억이 없는 노드는 접힌 채다")
    void untouchedNodesStayCollapsed() {
        // when
        ExpansionState.Restored restored = ExpansionState.empty().restoreIn(tree("/a/x", "/a/y"));

        // then
        assertThat(restored.expanded()).isEmpty();
        assertThat(restored.selected()).isEmpty();
    }

    @Test
    @DisplayName("이전 대화 · 더 보기 노드는 키가 없어 건너뛴다 — 대화가 붙어도 노드 키와 복원이 같다(#214)")
    void conversationNodesAreSkipped() {
        // given: /home/me/a 에 대화 7개(5개 + 더 보기)
        WorkHistory history = WorkHistory.empty(HistoryPolicy.DEFAULT).recordVisit(wsl("/home/me/a"), T0);
        history = history.recordVisit(wsl("/home/me/b"), T0);
        List<PastConversation> conversations = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            conversations.add(new PastConversation(
                    VisitSource.CLAUDE_CODE,
                    new ConversationId("c" + i),
                    wsl("/home/me/a"),
                    Optional.of("대화 " + i),
                    T0.plusSeconds(i)));
        }
        TreeQuery q = new TreeQuery("", false, List.of(UBUNTU));
        HistoryTree with = DirectoryTree.project(history, List.of(), conversations, q);
        HistoryTree without = DirectoryTree.project(history, List.of(), q);
        ExpansionState state = new ExpansionState(
                Set.of(ENV, folder("/home/me"), folder("/home/me/a")), Optional.of(folder("/home/me/a")));

        // when · then
        assertThat(Nodes.keys(with)).containsExactlyElementsOf(Nodes.keys(without));
        assertThat(state.restoreIn(with)).isEqualTo(state.restoreIn(without));
    }
}
