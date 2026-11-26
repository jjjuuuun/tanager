package dev.tanager.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 상태줄에 쌓인 알림(순수 값). 셸을 고른 뒤 시작이 설정을 다시 읽어 같은 경고가 또 오므로 같은 문장은 한 번만 쌓는다.
 *
 * @param messages 처음 본 순서
 */
record Notices(List<String> messages) {
    Notices {
        messages = List.copyOf(messages);
    }

    /** {@code more} 중 처음 보는 문장을 뒤에 붙인다. */
    Notices add(List<String> more) {
        List<String> next = new ArrayList<>(messages);
        for (String message : more) {
            if (!next.contains(message)) {
                next.add(message);
            }
        }
        return new Notices(next);
    }

    /** "첫 문장 (+n)". 알림이 없으면 비어 있다. */
    Optional<String> summary() {
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        String more = messages.size() > 1 ? " (+" + (messages.size() - 1) + ")" : "";
        return Optional.of(messages.getFirst() + more);
    }
}
