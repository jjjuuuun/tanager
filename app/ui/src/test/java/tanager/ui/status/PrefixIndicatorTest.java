package tanager.ui.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.ui.theme.SoftDarkLaf;

/** 상태줄의 prefix 모드 표시(FR-LAY-08, SDD §4 ⑤ 상태줄, #193). */
class PrefixIndicatorTest {
    private LookAndFeel previous;

    @BeforeEach
    void installTheme() throws Exception {
        onEdt(() -> {
            previous = UIManager.getLookAndFeel();
            UIManager.setLookAndFeel(new SoftDarkLaf());
            return null;
        });
    }

    @AfterEach
    void restoreTheme() throws Exception {
        onEdt(() -> {
            UIManager.setLookAndFeel(previous);
            return null;
        });
    }

    @Test
    @DisplayName("처음에는 숨어 있고, prefix 모드 동안 PREFIX 가 보이고, 끝나면 사라진다")
    void showsWhilePrefixModeIsOn() throws Exception {
        // given
        PrefixIndicator indicator = onEdt(PrefixIndicator::new);
        JLabel label = onEdt(indicator::component);
        boolean initially = onEdt(label::isVisible);

        // when
        onEdt(() -> {
            indicator.show(true);
            return null;
        });
        boolean during = onEdt(label::isVisible);
        String text = onEdt(label::getText);
        onEdt(() -> {
            indicator.show(false);
            return null;
        });

        // then
        assertThat(initially).isFalse();
        assertThat(during).isTrue();
        assertThat(text).isEqualTo("PREFIX");
        assertThat(onEdt(label::isVisible)).isFalse();
    }

    private static <T> T onEdt(Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(task.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }
}
