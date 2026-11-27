package tanager.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.TransferHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.DropZone;
import tanager.domain.layout.Edge;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.shared.PaneId;
import tanager.ui.sidebar.FolderTransfer;

/**
 * 창 영역의 폴더 받기(#151, FR-LAY-13). 끌어다 놓기용 {@code TransferSupport} 는 Swing 밖에서 만들 수 없어, 받기 판단은 {@link
 * FolderDrops#hover} · {@link FolderDrops#drop} 으로 시험하고 {@code canImport} · {@code importData} 는 놓기가 아닌 지원(붙여넣기)을
 * 거절하는지만 본다.
 */
class FolderDropsTest {
    private static final int GAP = 6;
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final WorkspacePath WORK = WorkspacePath.of(new ShellEnvironment.Wsl("Ubuntu"), "/home/me/work")
            .value()
            .orElseThrow();

    private record Open(WorkspacePath path, DropZone zone) {}

    private final List<Open> opens = new ArrayList<>();

    /** p1 | p2 를 606 × 400 에 놓은 창 영역. */
    private LayoutView view() {
        LayoutNode two =
                new LayoutNode.Split(Orientation.ROW, Ratio.HALF, new LayoutNode.Leaf(P1), new LayoutNode.Leaf(P2));
        LayoutView view = new LayoutView(
                two,
                GAP,
                () -> new PaneMinimum(0, 0),
                (path, ratio) -> {},
                new LayoutView.Drops((pane, zone) -> {}, (path, zone) -> opens.add(new Open(path, zone))));
        Map<PaneId, JComponent> components = new LinkedHashMap<>();
        components.put(P1, new JPanel());
        components.put(P2, new JPanel());
        view.component().setSize(606, 400);
        view.show(two, components);
        return view;
    }

    private FolderDrops drops(LayoutView view) {
        return (FolderDrops) view.component().getTransferHandler();
    }

    @Test
    @DisplayName("창 영역은 폴더 받기 처리기를 단다")
    void layoutInstallsHandler() {
        // when · then
        assertThat(view().component().getTransferHandler()).isInstanceOf(FolderDrops.class);
    }

    @Test
    @DisplayName("폴더면 창 위에서만 받는다 — 폴더가 아닌 것(글자)은 어디서나 받지 않는다")
    void hoverAcceptsOnlyFoldersOverPanes() {
        // given
        FolderDrops drops = drops(view());
        DataFlavor[] folder = FolderTransfer.of(WORK).getTransferDataFlavors();
        DataFlavor[] text = new StringSelection("C:\\work").getTransferDataFlavors();

        // when · then
        assertThat(drops.hover(folder, 450, 200)).isTrue();
        assertThat(drops.hover(folder, 303, 200)).isFalse();
        assertThat(drops.hover(text, 450, 200)).isFalse();
    }

    @Test
    @DisplayName("폴더를 창 오른쪽 띠에 놓으면 그 경로를 그 창 오른쪽에 연다, 글자는 열지 않는다")
    void dropOpensFolder() {
        // given
        FolderDrops drops = drops(view());
        Transferable text = new StringSelection("C:\\work");

        // when
        boolean folder = drops.drop(FolderTransfer.of(WORK), 600, 200);
        boolean other = drops.drop(text, 600, 200);

        // then
        assertThat(folder).isTrue();
        assertThat(other).isFalse();
        assertThat(opens).containsExactly(new Open(WORK, new DropZone.Side(P2, Edge.RIGHT)));
    }

    @Test
    @DisplayName("끌어다 놓기가 아닌 붙여넣기는 받지 않는다")
    void pasteIsRejected() {
        // given
        LayoutView view = view();
        TransferHandler.TransferSupport paste =
                new TransferHandler.TransferSupport(view.component(), FolderTransfer.of(WORK));

        // when · then
        assertThat(drops(view).canImport(paste)).isFalse();
        assertThat(drops(view).importData(paste)).isFalse();
        assertThat(opens).isEmpty();
    }
}
