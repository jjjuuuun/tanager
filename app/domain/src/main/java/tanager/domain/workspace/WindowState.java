package tanager.domain.workspace;

import java.util.Objects;
import java.util.Optional;

/**
 * 앱 창의 위치 · 크기 · 최대화와 패널 상태(SDD §5.4, FR-RES-03).
 *
 * @param bounds 창의 자리. 비면 OS 가 정한 자리. 화면 밖인지는 UI 가 판정한다
 * @param maximized 최대화했는지. 최대화 전 자리는 {@code bounds} 다
 */
public record WindowState(Optional<Bounds> bounds, boolean maximized, PanelState sidebar, PanelState toolkit) {
    /** 처음 여는 창: OS 가 정한 자리 · 사이드바 236px · 도구함 264px, 둘 다 보임(ADR-0006 배치 D). */
    public static final WindowState DEFAULT =
            new WindowState(Optional.empty(), false, new PanelState(true, 236), new PanelState(true, 264));

    public WindowState {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(sidebar, "sidebar");
        Objects.requireNonNull(toolkit, "toolkit");
    }
}
