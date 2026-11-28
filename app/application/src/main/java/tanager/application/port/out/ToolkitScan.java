package tanager.application.port.out;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.toolkit.ToolkitItem;
import tanager.domain.toolkit.ToolkitWarning;

/**
 * {@link ToolkitProvider#scan} 의 결과 — 읽은 항목, 파일마다의 경고, 이 결과를 다시 쓸 수 있는지 볼 지문(empty 면 캐시하지 않는다).
 */
public record ToolkitScan(List<ToolkitItem> items, List<ToolkitWarning> warnings, Optional<ScanStamp> stamp) {
    public ToolkitScan {
        items = List.copyOf(items);
        warnings = List.copyOf(warnings);
        Objects.requireNonNull(stamp, "stamp");
    }
}
