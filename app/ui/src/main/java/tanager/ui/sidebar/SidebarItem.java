package tanager.ui.sidebar;

import java.util.Objects;
import java.util.Optional;

/**
 * 사이드바 트리 노드 하나가 보일 모양(JTree 노드의 사용자 객체).
 *
 * @param key 펼침 · 선택을 이어 갈 정체
 * @param label 본 글자
 * @param secondary 보조 글자(별칭)
 * @param tooltip 툴팁
 * @param kind 그리는 방식
 * @param matched 검색어에 맞았는지(강조색으로 그린다)
 */
record SidebarItem(
        NodeKey key, String label, Optional<String> secondary, Optional<String> tooltip, Kind kind, boolean matched) {

    enum Kind {
        PINNED_SECTION,
        PINNED,
        ENVIRONMENT,
        FOLDER,
        SESSION_RUNNING,
        SESSION_EXITED
    }

    SidebarItem {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(secondary, "secondary");
        Objects.requireNonNull(tooltip, "tooltip");
        Objects.requireNonNull(kind, "kind");
    }

    // JTree 의 기본 글자 · 접근성 이름
    @Override
    public String toString() {
        return label;
    }
}
