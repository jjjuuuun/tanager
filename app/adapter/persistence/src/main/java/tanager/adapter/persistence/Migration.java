package tanager.adapter.persistence;

import java.util.List;
import java.util.Objects;
import tanager.application.port.out.WorkspaceLoadNotice;
import tools.jackson.databind.node.ObjectNode;

/**
 * state.json 한 버전 올리기(SDD §9.4): JSON 트리 → JSON 트리 순수 함수. 입력 트리를 바꾸지 않는다. Jackson 이 domain · application 에 들어가지 않게
 * 체인은 이 어댑터 안에 둔다(ADR-0013 개정).
 */
interface Migration {

    /** 읽는 버전. */
    long from();

    /** 쓰는 버전. */
    default long to() {
        return from() + 1;
    }

    /** {@code root}(버전 {@link #from()})를 {@link #to()} 모양으로 바꾼 새 트리. */
    Step apply(ObjectNode root);

    /** 바꾼 트리와 바꾸지 못해 버린 부분의 알림. */
    record Step(ObjectNode root, List<WorkspaceLoadNotice> notices) {
        public Step {
            Objects.requireNonNull(root, "root");
            notices = List.copyOf(notices);
        }
    }
}
