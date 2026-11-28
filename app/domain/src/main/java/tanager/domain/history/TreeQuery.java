package tanager.domain.history;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 트리 투영의 조건. {@code text} 는 검색어(앞뒤 공백뿐이면 거르지 않음), {@code showHidden} 은 숨긴 항목을 보일지,
 * {@code environmentOrder} 는 환경 노드 순서(셸 탐지 순서)다. {@code conversationsPerFolder} 는 폴더마다 보일 이전 대화 수(0 이면 대화를
 * 보이지 않는다), {@code expandedConversations} 는 대화를 모두 보일 폴더다. 불변식: conversationsPerFolder ≥ 0.
 */
public record TreeQuery(
        String text,
        boolean showHidden,
        List<ShellEnvironment> environmentOrder,
        int conversationsPerFolder,
        Set<WorkspacePath> expandedConversations) {

    /** 폴더마다 보일 대화 수의 기본값. 사이드바 시안(#203)이 정해지기 전까지의 값이다. */
    public static final int DEFAULT_CONVERSATIONS_PER_FOLDER = 5;

    public TreeQuery {
        Objects.requireNonNull(text, "text");
        environmentOrder = List.copyOf(environmentOrder);
        expandedConversations = Set.copyOf(expandedConversations);
        if (conversationsPerFolder < 0) {
            throw new IllegalArgumentException("폴더별 대화 수는 0 이상이어야 한다: " + conversationsPerFolder);
        }
    }

    /** 대화는 기본 수만큼 보이고 펼친 폴더가 없는 조건. */
    public TreeQuery(String text, boolean showHidden, List<ShellEnvironment> environmentOrder) {
        this(text, showHidden, environmentOrder, DEFAULT_CONVERSATIONS_PER_FOLDER, Set.of());
    }
}
