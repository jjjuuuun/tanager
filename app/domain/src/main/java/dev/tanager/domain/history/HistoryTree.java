package dev.tanager.domain.history;

import java.util.List;

/** 사이드바가 그리는 이력 트리 전체 — 위의 '고정됨' 섹션과 아래의 환경별 폴더 트리. */
public record HistoryTree(List<PinnedEntry> pinned, List<EnvironmentNode> environments) {
    public HistoryTree {
        pinned = List.copyOf(pinned);
        environments = List.copyOf(environments);
    }
}
