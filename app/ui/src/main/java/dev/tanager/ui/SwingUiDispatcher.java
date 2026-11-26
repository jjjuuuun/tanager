package dev.tanager.ui;

import dev.tanager.application.port.out.UiDispatcher;
import java.util.Objects;
import javax.swing.SwingUtilities;

/** {@link UiDispatcher} 의 Swing 구현. EDT 에서 불려도 바로 돌리지 않고 뒤에 넣어 넘긴 순서를 지킨다. */
public final class SwingUiDispatcher implements UiDispatcher {

    @Override
    public void dispatch(Runnable task) {
        SwingUtilities.invokeLater(Objects.requireNonNull(task, "task"));
    }
}
