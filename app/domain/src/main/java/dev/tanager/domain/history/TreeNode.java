package dev.tanager.domain.history;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 환경 노드 아래의 트리 노드 — 폴더 또는 그 폴더에서 도는 세션. */
public sealed interface TreeNode {

    /**
     * 폴더. 자식 하나뿐인 방문 없는 폴더는 자식과 합쳐 한 줄이 되므로 {@code label} 은 여러 세그먼트일 수 있고, {@code path} 는 그
     * 마지막 세그먼트의 경로다. {@code visit} 은 보이는 방문 기록(숨긴 항목은 showHidden 일 때만). 자식 폴더의 경로는 모두 path 아래.
     */
    record Folder(
            WorkspacePath path,
            String label,
            Optional<VisitedDirectory> visit,
            boolean matched,
            List<TreeNode> children)
            implements TreeNode {
        public Folder {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(visit, "visit");
            if (label.isBlank()) {
                throw new IllegalArgumentException("폴더 라벨이 비어 있다: " + path);
            }
            children = List.copyOf(children);
        }
    }

    /** 폴더(또는 환경) 아래에 매단 세션. */
    record Session(SessionLeaf session) implements TreeNode {
        public Session {
            Objects.requireNonNull(session, "session");
        }
    }
}
