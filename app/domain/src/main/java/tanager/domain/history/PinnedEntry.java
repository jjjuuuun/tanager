package tanager.domain.history;

import java.util.Objects;

/**
 * '고정됨' 섹션의 항목 하나. {@code label} 은 별칭 또는 마지막 세그먼트, {@code matched} 는 검색어에 맞았는지, {@code missing} 은 더 이상
 * 없다고 확인됐는지(FR-HIS-08).
 */
public record PinnedEntry(VisitedDirectory entry, String label, boolean matched, boolean missing) {
    public PinnedEntry {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("고정 항목 라벨이 비어 있다");
        }
    }

    /** 사라졌다고 확인되지 않은 항목. */
    public PinnedEntry(VisitedDirectory entry, String label, boolean matched) {
        this(entry, label, matched, false);
    }
}
