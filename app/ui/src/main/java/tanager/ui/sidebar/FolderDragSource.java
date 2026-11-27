package tanager.ui.sidebar;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.Transferable;
import java.util.Objects;
import java.util.Optional;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;

/**
 * 사이드바 트리에서 폴더를 끌어 내보낸다(#151, FR-LAY-13). 고른 노드가 폴더(고정 항목 포함)일 때만 그 경로를 내보내고, 세션 · 환경 노드는 끌리지
 * 않는다. 복사만 한다 — 트리에서 아무것도 빠지지 않는다. 받는 쪽은 창 영역({@code tanager.ui.layout.FolderDrops})이다.
 */
final class FolderDragSource extends TransferHandler {
    private final Optional<TransferHandler> clipboard;

    /** @param clipboard 트리의 원래 처리기. 복사(Ctrl+C)는 지금처럼 그것이 한다(노드 글자) */
    FolderDragSource(Optional<TransferHandler> clipboard) {
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
    }

    @Override
    public void exportToClipboard(JComponent comp, Clipboard clip, int action) {
        clipboard.ifPresent(original -> original.exportToClipboard(comp, clip, action));
    }

    @Override
    public int getSourceActions(JComponent c) {
        return COPY;
    }

    // TransferHandler 계약: 내보낼 것이 없으면 null 이어야 끌기가 시작되지 않는다
    @Override
    protected Transferable createTransferable(JComponent c) {
        return c instanceof JTree tree ? exported(tree).orElse(null) : null;
    }

    /** 고른 노드가 폴더면 그 경로를 넘기는 값. */
    Optional<Transferable> exported(JTree tree) {
        return Optional.ofNullable(tree.getSelectionPath())
                .map(path -> ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject())
                .filter(SidebarItem.class::isInstance)
                .flatMap(item -> ((SidebarItem) item).target())
                .filter(MenuTarget.Folder.class::isInstance)
                .map(target -> FolderTransfer.of(((MenuTarget.Folder) target).path()));
    }
}
