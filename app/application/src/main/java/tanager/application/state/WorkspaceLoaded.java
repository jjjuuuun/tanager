package tanager.application.state;

import java.util.ArrayList;
import java.util.List;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.WorkspaceLoadNotice;

/**
 * 작업 공간을 읽으며 생긴 일(부분 손상 · 마이그레이션)을 상태줄에 알리는 명령(#179). 작업 공간 자체는 시작 상태로 이미 들어가 있어 상태를
 * 바꾸지 않는다 — UI 가 이벤트를 구독한 뒤에 내야 알림을 잃지 않는다.
 */
public record WorkspaceLoaded(List<WorkspaceLoadNotice> notices) implements Command<Boolean> {
    public WorkspaceLoaded {
        notices = List.copyOf(notices);
    }

    @Override
    public Transition<Boolean> apply(AppState current) {
        List<AppEvent> events = new ArrayList<>();
        for (WorkspaceLoadNotice notice : notices) {
            events.add(new AppEvent.StoreNotice(new AppEvent.StoreProblem.WorkspaceLoaded(notice)));
        }
        return new Transition<>(current, events, true);
    }
}
