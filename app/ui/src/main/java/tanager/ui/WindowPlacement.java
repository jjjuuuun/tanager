package tanager.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import tanager.domain.layout.Rect;
import tanager.domain.workspace.Bounds;

/**
 * 저장된 창 자리를 지금 모니터들에 그대로 둘 수 있는지 판정한다(FR-RES-03, #180). 순수. 좌표는 Swing 논리 좌표 그대로다(DPI 배율 변환 없음).
 *
 * <p>기준은 제목 표시줄이다 — 그것을 잡아야 창을 옮길 수 있다. 제목 표시줄이 모니터 영역의 합 안에 가로 {@value #MIN_VISIBLE_WIDTH}px 이상,
 * 세로로 전부 보이면 그 자리다. 모니터를 빼 창이 화면 밖에 있게 되면 empty — 주 모니터의 기본 자리에 띄운다.
 */
final class WindowPlacement {
    /** 판정에 쓰는 제목 표시줄 높이(창 위쪽 띠). 창이 이보다 낮으면 창 높이 전체다. */
    static final int TITLE_BAR_HEIGHT = 32;
    /** 제목 표시줄이 가로로 이만큼은 보여야 잡을 수 있다. */
    static final int MIN_VISIBLE_WIDTH = 48;

    private WindowPlacement() {}

    /** {@code saved} 의 제목 표시줄이 {@code screens}(모니터 영역들) 안에 충분히 보이면 그 자리, 아니면 empty. */
    static Optional<Bounds> fit(Bounds saved, List<Rect> screens) {
        Objects.requireNonNull(saved, "saved");
        List<Rect> monitors = List.copyOf(screens);
        long left = saved.x();
        long right = left + saved.width();
        long top = saved.y();
        long bottom = top + Math.min(TITLE_BAR_HEIGHT, saved.height());
        // 모니터 가장자리에서만 덮임이 바뀐다 — 그 사이 구간마다 한 열을 보면 된다
        TreeSet<Long> edges = new TreeSet<>(List.of(left, right));
        for (Rect screen : monitors) {
            for (long edge : List.of((long) screen.x(), (long) screen.x() + screen.width())) {
                if (edge > left && edge < right) {
                    edges.add(edge);
                }
            }
        }
        List<Long> columns = List.copyOf(edges);
        long visible = 0;
        for (int i = 1; i < columns.size(); i++) {
            long from = columns.get(i - 1);
            if (covers(monitors, from, top, bottom)) {
                visible += columns.get(i) - from;
            }
        }
        return visible >= MIN_VISIBLE_WIDTH ? Optional.of(saved) : Optional.empty();
    }

    // 열 column 의 [top, bottom) 이 모니터들로 빈틈없이 덮이는가(위아래로 붙은 모니터 사이에 걸쳐도 된다)
    private static boolean covers(List<Rect> monitors, long column, long top, long bottom) {
        List<Rect> crossing = new ArrayList<>();
        for (Rect screen : monitors) {
            if (column >= screen.x() && column < (long) screen.x() + screen.width()) {
                crossing.add(screen);
            }
        }
        crossing.sort(Comparator.comparingInt(Rect::y));
        long reach = top;
        for (Rect screen : crossing) {
            if (screen.y() > reach) {
                return false;
            }
            reach = Math.max(reach, (long) screen.y() + screen.height());
            if (reach >= bottom) {
                return true;
            }
        }
        return reach >= bottom;
    }
}
