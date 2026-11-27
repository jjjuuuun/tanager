package tanager.domain.history;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 환경 노드 아래의 트리 노드 — 폴더 또는 그 폴더에서 도는 세션. */
public sealed interface TreeNode {

    /**
     * 폴더. 자식 하나뿐인 방문 없는 폴더는 자식과 합쳐 한 줄이 되므로 {@code label} 은 여러 세그먼트일 수 있고, {@code path} 는 그
     * 마지막 세그먼트의 경로다. {@code visit} 은 보이는 방문 기록(숨긴 항목은 showHidden 일 때만). 자식 폴더의 경로는 모두 path 아래.
     *
     * <p>표기(대소문자): 방문 노드의 {@code path} 는 방문 기록의 표기다. 방문 없는 노드는 그 아래의 보이는 방문 경로 중 문자열 순서로 가장
     * 앞선 것의 앞부분을 쓰고, 그런 방문이 없으면 (번호, 창 ID) 순으로 가장 앞선 세션 cwd 의 앞부분을 쓴다. {@code label} 은 {@code
     * path().path()} 의 끝부분과 글자까지 같다(환경 바로 아래 폴더는 전체 경로).
     *
     * <p>주의: 펼침 · 선택 상태는 {@code label} 이 아니라 {@code path}({@link WorkspacePath}) 로 기억한다. 라벨은 압축 여부와 표기가
     * 바뀌면(형제가 생기거나 다른 표기로 방문하면) 달라진다.
     *
     * <p>{@code missing} 은 방문 기록이 있는 폴더가 더 이상 없다고 확인됐는지다(FR-HIS-08). 모르면 {@code false}.
     */
    record Folder(
            WorkspacePath path,
            String label,
            Optional<VisitedDirectory> visit,
            boolean matched,
            List<TreeNode> children,
            boolean missing)
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

        /** 사라졌다고 확인되지 않은 폴더. */
        public Folder(
                WorkspacePath path,
                String label,
                Optional<VisitedDirectory> visit,
                boolean matched,
                List<TreeNode> children) {
            this(path, label, visit, matched, children, false);
        }
    }

    /** 폴더(또는 환경) 아래에 매단 세션. */
    record Session(SessionLeaf session) implements TreeNode {
        public Session {
            Objects.requireNonNull(session, "session");
        }
    }
}
