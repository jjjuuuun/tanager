package tanager.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tanager.application.port.in.AppEvent;
import tanager.ui.i18n.NoticeText;

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

    /** 상태줄에 보일 이벤트의 문장. 저장소 알림(불러오기 복구 · 읽기 전용 · 저장 실패)과 설정 다시 읽기 알림만 보인다. */
    static Optional<String> messageOf(AppEvent event) {
        return switch (event) {
            case AppEvent.StoreNotice(var problem) -> Optional.of(NoticeText.DEFAULT.of(problem));
            case AppEvent.SettingsNotice(var problem) -> Optional.of(NoticeText.DEFAULT.of(problem));
            case AppEvent.SettingsChanged changed -> Optional.empty();
            case AppEvent.HistoryChanged changed -> Optional.empty();
            case AppEvent.MissingPathsChanged missing -> Optional.empty();
            case AppEvent.SessionCwdChanged moved -> Optional.empty();
            case AppEvent.SessionsChanged sessions -> Optional.empty();
            case AppEvent.EnvironmentsChanged environments -> Optional.empty();
            case AppEvent.LayoutChanged layout -> Optional.empty();
            case AppEvent.LastPaneClosed closed -> Optional.empty();
        };
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
