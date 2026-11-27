package tanager.domain.layout;

import java.util.Objects;

/**
 * Split 하나의 경계선(FR-LAY-11). 마우스가 {@code hit} 안에 있으면 그 경계선을 잡고, 끄는 동안 {@link LayoutGeometry#ratioAt} 이
 * {@code area} · {@code gap} 으로 새 비율을 낸다 — 놓을 때 {@link LayoutNode#resizeTo} 로 {@code path} 의 비율을 바꾼다.
 *
 * @param orientation 그 Split 의 방향(ROW 면 세로 경계선)
 * @param hit 잡는 사각형. 간격 가운데에 놓인 폭 hitPx 의 띠
 * @param area 그 Split 이 차지하는 영역 전체
 * @param gap 그 Split 을 그릴 때 쓴 창 사이 간격 — 비율과 픽셀을 정확히 바꾸려면 필요하다
 */
public record Divider(SplitPath path, Orientation orientation, Rect hit, Rect area, int gap) {
    public Divider {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(hit, "hit");
        Objects.requireNonNull(area, "area");
        if (gap < 0) {
            throw new IllegalArgumentException("간격은 음수일 수 없다: " + gap);
        }
    }
}
