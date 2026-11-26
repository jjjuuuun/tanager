package dev.tanager.domain.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResultTest {

    @Test
    @DisplayName("ok 는 값을, err 는 오류를 담는다")
    void okAndErrHoldTheirContent() {
        // given / when
        Result<Integer, String> ok = Result.ok(1);
        Result<Integer, String> err = Result.err("없음");

        // then
        assertThat(ok.isOk()).isTrue();
        assertThat(err.isOk()).isFalse();
        assertThat(ok.value()).contains(1);
        assertThat(ok.error()).isEmpty();
        assertThat(err.value()).isEmpty();
        assertThat(err.error()).contains("없음");
    }

    @Test
    @DisplayName("null 은 값으로도 오류로도 받지 않는다")
    void rejectsNull() {
        assertThatThrownBy(() -> Result.ok(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Result.err(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("map 은 ok 의 값만 바꾸고 err 는 그대로 둔다")
    void mapTransformsOnlyOk() {
        Result<Integer, String> ok = Result.ok(2);
        Result<Integer, String> err = Result.err("e");

        assertThat(ok.map(v -> v * 10)).isEqualTo(Result.ok(20));
        assertThat(err.map(v -> v * 10)).isEqualTo(Result.err("e"));
    }

    @Test
    @DisplayName("mapError 는 err 의 오류만 바꾼다")
    void mapErrorTransformsOnlyErr() {
        Result<Integer, String> ok = Result.ok(2);
        Result<Integer, String> err = Result.err("e");

        assertThat(ok.mapError(String::length)).isEqualTo(Result.ok(2));
        assertThat(err.mapError(e -> e + "!")).isEqualTo(Result.err("e!"));
    }

    @Test
    @DisplayName("flatMap 은 ok 일 때만 다음 단계를 잇는다")
    void flatMapChainsOnlyOk() {
        Result<Integer, String> ok = Result.ok(4);
        Result<Integer, String> err = Result.err("e");

        assertThat(ok.flatMap(v -> v > 3 ? Result.ok(v - 3) : Result.err("작음"))).isEqualTo(Result.ok(1));
        assertThat(ok.flatMap(v -> Result.<Integer, String>err("실패"))).isEqualTo(Result.err("실패"));
        assertThat(err.flatMap(v -> Result.ok(v + 1))).isEqualTo(Result.err("e"));
    }

    @Test
    @DisplayName("fold 는 두 경우를 하나의 값으로 모은다")
    void foldCollapsesBothCases() {
        Result<Integer, String> ok = Result.ok(7);
        Result<Integer, String> err = Result.err("e");

        String fromOk = ok.fold(v -> "값 " + v, e -> "오류 " + e);
        String fromErr = err.fold(v -> "값 " + v, e -> "오류 " + e);

        assertThat(fromOk).isEqualTo("값 7");
        assertThat(fromErr).isEqualTo("오류 e");
    }

    @Property
    void mapWithIdentityKeepsResult(@ForAll int v, @ForAll String e) {
        assertThat(Result.<Integer, String>ok(v).map(x -> x)).isEqualTo(Result.ok(v));
        assertThat(Result.<Integer, String>err(e).map(x -> x)).isEqualTo(Result.err(e));
    }

    @Property
    void valueAndErrorAreExclusive(@ForAll int v, @ForAll boolean asOk) {
        Result<Integer, Integer> r = asOk ? Result.ok(v) : Result.err(v);
        Optional<Integer> value = r.value();
        Optional<Integer> error = r.error();
        assertThat(value.isPresent()).isNotEqualTo(error.isPresent());
        assertThat(value.isPresent()).isEqualTo(r.isOk());
    }
}
