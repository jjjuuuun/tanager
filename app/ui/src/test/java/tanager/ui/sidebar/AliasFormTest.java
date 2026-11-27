package tanager.ui.sidebar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 별칭 입력칸: 확인을 누르면 검사하고, 틀리면 칸 안에 오류를 보이며 닫히지 않는다(#86). */
class AliasFormTest {
    private javax.swing.LookAndFeel previous;

    @org.junit.jupiter.api.BeforeEach
    void installTheme() {
        onEdt(() -> {
            previous = javax.swing.UIManager.getLookAndFeel();
            try {
                javax.swing.UIManager.setLookAndFeel(new tanager.ui.theme.SoftDarkLaf());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @org.junit.jupiter.api.AfterEach
    void restoreTheme() {
        onEdt(() -> {
            try {
                javax.swing.UIManager.setLookAndFeel(previous);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
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

    @Test
    @DisplayName("별칭을 넣고 확인하면 그 별칭이다")
    void acceptsAlias() {
        Optional<Optional<String>> result = onEdt(() -> {
            AliasForm form = new AliasForm(Optional.empty());
            form.field().setText("인프라");
            return form.submit();
        });

        assertThat(result).contains(Optional.of("인프라"));
    }

    @Test
    @DisplayName("빈 값으로 확인하면 별칭을 지운다")
    void blankClearsAlias() {
        Optional<Optional<String>> result = onEdt(() -> {
            AliasForm form = new AliasForm(Optional.of("예전"));
            form.field().setText("   ");
            return form.submit();
        });

        assertThat(result).contains(Optional.empty());
    }

    @Test
    @DisplayName("65자면 오류 문구를 보이고 결과가 없다(닫히지 않는다)")
    void tooLongShowsErrorAndStaysOpen() {
        // when
        AliasForm form = onEdt(() -> new AliasForm(Optional.empty()));
        Optional<Optional<String>> result = onEdt(() -> {
            form.field().setText("가".repeat(65));
            return form.submit();
        });

        // then
        assertThat(result).isEmpty();
        assertThat(onEdt(() -> form.error().isVisible())).isTrue();
        assertThat(onEdt(() -> form.error().getText())).isNotBlank();
    }

    @Test
    @DisplayName("지금 별칭이 칸에 미리 들어 있다")
    void prefillsCurrentAlias() {
        assertThat(onEdt(() -> new AliasForm(Optional.of("작업")).field().getText()))
                .isEqualTo("작업");
    }
}
