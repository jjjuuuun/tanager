package tanager.ui.sidebar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JTree;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.QueryHistoryTreeUseCase;
import tanager.domain.history.DirectoryTree;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.HistoryTree;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.TreeQuery;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.ui.theme.SoftDarkLaf;

/** 사이드바 패널(Swing). 다시 투영해도 펼침 · 선택이 남고, 몰린 요청은 한 번만 다시 그린다(#84). */
class SidebarPanelTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final Instant T0 = Instant.parse("2026-11-27T00:00:00Z");

    private final Queue<Runnable> uiQueue = new ArrayDeque<>();
    private final Executor ui = uiQueue::add;
    private final AtomicReference<HistoryTree> current = new AtomicReference<>();
    private final AtomicInteger queries = new AtomicInteger();
    private final QueryHistoryTreeUseCase query = (text, showHidden) -> {
        queries.incrementAndGet();
        return current.get();
    };
    private LookAndFeel previous;

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private static HistoryTree tree(String... visited) {
        WorkHistory history = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (String raw : visited) {
            history = history.recordVisit(wsl(raw), T0);
        }
        return DirectoryTree.project(history, List.of(), new TreeQuery("", false, List.of(UBUNTU)));
    }

    private static <T> T onEdt(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return result.get();
    }

    private static List<String> visibleRows(JTree tree) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            Object node = ((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject();
            rows.add(((SidebarItem) node).label());
        }
        return rows;
    }

    private static TreePath pathOf(JTree tree, String label) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath path = tree.getPathForRow(i);
            Object node = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (((SidebarItem) node).label().equals(label)) {
                return path;
            }
        }
        throw new AssertionError("행 없음: " + label + " in " + visibleRows(tree));
    }

    @BeforeEach
    void installTheme() {
        onEdt(() -> {
            previous = UIManager.getLookAndFeel();
            try {
                UIManager.setLookAndFeel(new SoftDarkLaf());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @AfterEach
    void restoreTheme() {
        onEdt(() -> {
            try {
                UIManager.setLookAndFeel(previous);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @Test
    @DisplayName("처음 그리면 환경 노드가 펼쳐져 있고 폴더 · 환경 글자가 보인다")
    void firstRefreshExpandsEnvironments() {
        // given
        current.set(tree("/home/me/a", "/home/me/b"));

        // when
        List<String> rows = onEdt(() -> {
            SidebarPanel panel = new SidebarPanel(query, ui);
            panel.refresh();
            return visibleRows(panel.tree());
        });

        // then
        assertThat(rows).containsExactly("WSL · Ubuntu", "/home/me");
    }

    @Test
    @DisplayName("다시 투영해도 펼친 폴더와 선택이 그대로다")
    void refreshKeepsExpansionAndSelection() {
        // given
        current.set(tree("/home/me/a", "/home/me/b"));
        SidebarPanel panel = onEdt(() -> {
            SidebarPanel created = new SidebarPanel(query, ui);
            created.refresh();
            TreePath home = pathOf(created.tree(), "/home/me");
            created.tree().expandPath(home);
            created.tree().setSelectionPath(home);
            return created;
        });

        // when: 새 방문으로 트리가 바뀐다
        current.set(tree("/home/me/a", "/home/me/b", "/home/me/c"));
        List<String> rows = onEdt(() -> {
            panel.refresh();
            return visibleRows(panel.tree());
        });

        // then
        assertThat(rows).containsExactly("WSL · Ubuntu", "/home/me", "a", "b", "c");
        String selected = onEdt(() -> ((SidebarItem) ((DefaultMutableTreeNode)
                                panel.tree().getSelectionPath().getLastPathComponent())
                        .getUserObject())
                .label());
        assertThat(selected).isEqualTo("/home/me");
    }

    @Test
    @DisplayName("요청이 10번 몰려도 EDT 한 틱에 한 번만 다시 투영한다")
    void coalescesBurstOfRequests() {
        // given
        current.set(tree("/home/me"));
        SidebarPanel panel = onEdt(() -> new SidebarPanel(query, ui));

        // when
        onEdt(() -> {
            for (int i = 0; i < 10; i++) {
                panel.requestRefresh();
            }
            return null;
        });

        // then
        assertThat(uiQueue).hasSize(1);
        onEdt(() -> {
            uiQueue.poll().run();
            return null;
        });
        assertThat(queries).hasValue(1);
        onEdt(() -> {
            panel.requestRefresh();
            return null;
        });
        assertThat(uiQueue).hasSize(1);
    }
}
