package tanager.ui.sidebar;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.history.WorkspacePath;

/**
 * 사이드바 폴더를 창 영역으로 끌 때 넘기는 값(#151, FR-LAY-13). 같은 JVM 안에서만 쓰는 형식이라 직렬화 없이 {@link WorkspacePath} 를 그대로
 * 넘긴다 — 다른 프로그램 · 글자 칸에는 놓이지 않는다.
 */
public final class FolderTransfer {
    /** 폴더 경로 형식. */
    public static final DataFlavor FLAVOR = new DataFlavor(
            DataFlavor.javaJVMLocalObjectMimeType + ";class=" + WorkspacePath.class.getName(), "Tanager folder");

    private FolderTransfer() {}

    /** {@code path} 하나를 넘기는 값. */
    public static Transferable of(WorkspacePath path) {
        return new Payload(Objects.requireNonNull(path, "path"));
    }

    /** {@code flavors} 에 폴더 형식이 있는가(끄는 중에는 값을 꺼내지 않고 형식만 본다). */
    public static boolean supports(DataFlavor[] flavors) {
        return Arrays.asList(flavors).contains(FLAVOR);
    }

    /** 넘어온 폴더 경로. 폴더 형식이 아니거나 꺼내지 못하면 비어 있다. */
    public static Optional<WorkspacePath> read(Transferable transferable) {
        if (!transferable.isDataFlavorSupported(FLAVOR)) {
            return Optional.empty();
        }
        try {
            return transferable.getTransferData(FLAVOR) instanceof WorkspacePath path
                    ? Optional.of(path)
                    : Optional.empty();
        } catch (UnsupportedFlavorException | IOException e) {
            return Optional.empty();
        }
    }

    private record Payload(WorkspacePath path) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return FLAVOR.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return path;
        }
    }
}
