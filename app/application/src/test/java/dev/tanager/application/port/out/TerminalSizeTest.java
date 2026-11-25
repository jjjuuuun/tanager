package dev.tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TerminalSizeTest {

    @Test
    @DisplayName("열이 0 이면 거부한다")
    void rejectsZeroColumns() {
        assertThatThrownBy(() -> new TerminalSize(0, 24)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("행이 32767 을 넘으면 거부한다")
    void rejectsTooManyRows() {
        assertThatThrownBy(() -> new TerminalSize(80, 40000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기본 크기는 80x24")
    void defaultIs80x24() {
        assertThat(TerminalSize.DEFAULT).isEqualTo(new TerminalSize(80, 24));
    }

    @Property
    @Label("1..32767 범위의 열 · 행은 항상 만들어지고 값을 보존한다")
    void inRangeAlwaysValid(
            @ForAll @IntRange(min = 1, max = 32767) int columns, @ForAll @IntRange(min = 1, max = 32767) int rows) {
        TerminalSize size = new TerminalSize(columns, rows);

        assertThat(size.columns()).isEqualTo(columns);
        assertThat(size.rows()).isEqualTo(rows);
    }

    @Property
    @Label("범위를 벗어난 열 또는 행은 항상 거부된다")
    void outOfRangeAlwaysRejected(@ForAll @IntRange(min = 1, max = 32767) int valid, @ForAll int bad) {
        boolean outOfRange = bad < 1 || bad > 32767;
        Assume.that(outOfRange);

        assertThatThrownBy(() -> new TerminalSize(bad, valid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TerminalSize(valid, bad)).isInstanceOf(IllegalArgumentException.class);
    }
}
