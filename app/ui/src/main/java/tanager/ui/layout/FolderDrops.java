package tanager.ui.layout;

import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.util.Objects;
import java.util.Optional;
import javax.swing.TransferHandler;
import tanager.domain.history.WorkspacePath;
import tanager.ui.sidebar.FolderTransfer;

/**
 * 창 영역이 사이드바 폴더를 받는다(#151, FR-LAY-13). 끄는 동안 점 아래 창의 놓을 곳을 {@link LayoutView} 가 보이고, 놓으면 그 경로 · 자리로
 * 연다. 폴더가 아닌 것과 붙여넣기는 받지 않는다. 창 영역을 벗어날 때 미리보기를 지우는 일은 {@link LayoutView} 가 놓기 대상에 단 리스너가 한다 —
 * {@link TransferHandler} 에는 벗어남 알림이 없다.
 */
final class FolderDrops extends TransferHandler {
    private final LayoutView view;

    FolderDrops(LayoutView view) {
        this.view = Objects.requireNonNull(view, "view");
    }

    @Override
    public boolean canImport(TransferSupport support) {
        if (!support.isDrop()) {
            return false;
        }
        Point at = support.getDropLocation().getDropPoint();
        return hover(support.getDataFlavors(), at.x, at.y);
    }

    @Override
    public boolean importData(TransferSupport support) {
        if (!support.isDrop()) {
            return false;
        }
        Point at = support.getDropLocation().getDropPoint();
        return drop(support.getTransferable(), at.x, at.y);
    }

    /** 끄는 중: 폴더 형식이고 점이 창 위면 받는다. 창 영역 좌표다. */
    boolean hover(DataFlavor[] flavors, int x, int y) {
        if (!FolderTransfer.supports(flavors)) {
            view.endFolderHover();
            return false;
        }
        return view.hoverFolder(x, y);
    }

    /** 놓음: 폴더를 창 위에 놓았으면 연다. */
    boolean drop(Transferable transferable, int x, int y) {
        Optional<WorkspacePath> path = FolderTransfer.read(transferable);
        if (path.isEmpty()) {
            view.endFolderHover();
            return false;
        }
        return view.dropFolder(path.get(), x, y);
    }
}
