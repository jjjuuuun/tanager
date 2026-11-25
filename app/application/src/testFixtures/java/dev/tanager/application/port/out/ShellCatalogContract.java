package dev.tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ShellProfile;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link PlatformModule#shells()} 계약(SDD §13.2). 플랫폼 모듈마다 상속한다.
 * 하위 클래스는 결정적인(가짜 명령 출력을 쓰는) 모듈을 돌려준다.
 */
@Tag("contract")
public abstract class ShellCatalogContract {

    /** 검사할 플랫폼 모듈. 호출마다 같은 결과를 내는 결정적 구성이어야 한다. */
    protected abstract PlatformModule platform();

    @Test
    @DisplayName("shells() 는 예외를 던지지 않는다")
    void doesNotThrow() {
        assertThatCode(() -> platform().shells()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shells() 결과는 수정할 수 없다")
    void isUnmodifiable() {
        List<ShellProfile> shells = platform().shells();
        assertThatThrownBy(() -> shells.add(shells.isEmpty() ? null : shells.getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("프로파일 id 가 겹치지 않는다")
    void idsAreUnique() {
        List<ProfileId> ids = platform().shells().stream().map(ShellProfile::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("프로파일 이름이 비어 있지 않다")
    void namesAreNotBlank() {
        assertThat(platform().shells()).allSatisfy(p -> assertThat(p.name()).isNotBlank());
    }
}
