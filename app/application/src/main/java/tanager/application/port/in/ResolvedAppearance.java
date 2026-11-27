package tanager.application.port.in;

import java.util.Objects;
import tanager.application.port.out.Theme;

/**
 * 지금 화면에 쓸 테마와 움직임(FR-UI-01 · FR-UI-04). 설정({@code appearance})과 OS 값을 합친 결과다.
 *
 * @param theme 다크 또는 라이트
 * @param animations 전환 효과를 쓰는가. 끄면 전환은 즉시 최종 값이 된다
 */
public record ResolvedAppearance(Theme theme, boolean animations) {
    public ResolvedAppearance {
        Objects.requireNonNull(theme, "theme");
    }
}
