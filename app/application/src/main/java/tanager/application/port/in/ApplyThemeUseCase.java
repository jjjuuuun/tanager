package tanager.application.port.in;

import java.util.function.Consumer;
import tanager.application.port.out.Subscription;

/**
 * 지금 쓸 테마 · 움직임(FR-UI-01, ADR-0018). 설정({@link AppEvent.SettingsChanged})과 OS 변경을 합친다. 저장 상태가 아니라 StateActor 를
 * 거치지 않는다(SDD §5.7). 어느 메서드도 막지 않는다.
 */
public interface ApplyThemeUseCase {

    /** 마지막으로 정한 값. OS 값을 아직 읽기 전이면 OS 기본값(테마 모름 · 움직임 켬)으로 정한 값이다. 어느 스레드에서 불러도 된다. */
    ResolvedAppearance current();

    /**
     * 값이 바뀔 때만 {@code listener} 를 UI 스레드(EDT)에서 바뀐 순서대로 부른다. 구독할 때의 값은 보내지 않는다 — {@link #current()} 로 읽는다.
     * 구독이 하나라도 있는 동안만 OS 를 주기적으로 다시 읽는다.
     */
    Subscription subscribe(Consumer<ResolvedAppearance> listener);

    /** OS 값을 백그라운드에서 다시 읽는다. 창이 활성화될 때 UI 가 부른다. 이미 읽는 중이면 그 읽기가 끝난 뒤 한 번만 더 읽는다. */
    void refresh();
}
