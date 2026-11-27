package tanager.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 창이 뜨기 전에 온 알림을 모아 두었다가 창이 붙으면 한 번에 넘긴다. 이력 불러오기가 창 생성(글꼴 · 테마)보다 먼저 끝나 그 알림을 잃지 않게
 * 하려는 것이다. EDT 에서만 쓴다.
 */
final class NoticeInbox {
    private final List<String> buffered = new ArrayList<>();
    private Optional<Consumer<List<String>>> target = Optional.empty();

    /** 붙은 창이 있으면 바로 넘기고, 없으면 모아 둔다. */
    void accept(String message) {
        Objects.requireNonNull(message, "message");
        if (target.isPresent()) {
            target.get().accept(List.of(message));
        } else {
            buffered.add(message);
        }
    }

    /** 모아 둔 알림을 순서대로 넘기고 이후 알림은 바로 넘긴다. */
    void attach(Consumer<List<String>> window) {
        target = Optional.of(Objects.requireNonNull(window, "window"));
        if (!buffered.isEmpty()) {
            window.accept(List.copyOf(buffered));
            buffered.clear();
        }
    }
}
