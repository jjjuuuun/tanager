package tanager.ui.sidebar;

import java.util.Objects;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.PaneId;

/**
 * 사이드바 노드의 정체. 펼침 · 선택은 라벨이 아니라 이 값으로 기억한다 — 라벨은 압축 · 표기가 바뀌면 달라진다(#84). 폴더는 경로({@link
 * WorkspacePath}, 환경별 대소문자 규칙), 세션은 창 ID.
 */
sealed interface NodeKey {

    /** '고정됨' 섹션 머리. */
    record PinnedSection() implements NodeKey {}

    /** '고정됨' 섹션의 항목. */
    record Pinned(WorkspacePath path) implements NodeKey {
        public Pinned {
            Objects.requireNonNull(path, "path");
        }
    }

    /** 환경 노드. */
    record Environment(ShellEnvironment env) implements NodeKey {
        public Environment {
            Objects.requireNonNull(env, "env");
        }
    }

    /** 폴더(압축 노드면 마지막 세그먼트의 경로). */
    record Folder(WorkspacePath path) implements NodeKey {
        public Folder {
            Objects.requireNonNull(path, "path");
        }
    }

    /** 세션. */
    record Session(PaneId pane) implements NodeKey {
        public Session {
            Objects.requireNonNull(pane, "pane");
        }
    }
}
