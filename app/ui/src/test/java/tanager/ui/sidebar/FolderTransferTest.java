package tanager.ui.sidebar;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;

/** 사이드바 폴더를 끌 때 넘기는 값(#151). 같은 JVM 안에서만 쓰는 형식이라 직렬화하지 않고 경로 값 그대로 넘긴다. */
class FolderTransferTest {
    private static final WorkspacePath WORK = WorkspacePath.of(new ShellEnvironment.Wsl("Ubuntu"), "/home/me/work")
            .value()
            .orElseThrow();

    @Test
    @DisplayName("폴더 값은 폴더 형식 하나만 내놓고, 읽으면 같은 경로다")
    void roundTrip() {
        // given
        Transferable folder = FolderTransfer.of(WORK);

        // when · then
        assertThat(folder.getTransferDataFlavors()).containsExactly(FolderTransfer.FLAVOR);
        assertThat(folder.isDataFlavorSupported(FolderTransfer.FLAVOR)).isTrue();
        assertThat(folder.isDataFlavorSupported(DataFlavor.stringFlavor)).isFalse();
        assertThat(FolderTransfer.read(folder)).contains(WORK);
        assertThat(FolderTransfer.supports(folder.getTransferDataFlavors())).isTrue();
    }

    @Test
    @DisplayName("폴더가 아닌 값(글자)은 폴더로 읽히지 않는다")
    void otherDataIsNotAFolder() {
        // given
        Transferable text = new StringSelection("/home/me/work");

        // when · then
        assertThat(FolderTransfer.read(text)).isEmpty();
        assertThat(FolderTransfer.supports(text.getTransferDataFlavors())).isFalse();
    }
}
