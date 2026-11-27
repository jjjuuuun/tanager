package tanager.application.port.in;

import java.util.concurrent.CompletionStage;
import tanager.domain.workspace.WindowState;

/**
 * 메인 창 상태를 기록한다(FR-RES-03, #180). {@code AppState.window} 를 바꾸고, 바뀌면 {@link AppEvent.WindowChanged} 를 낸다 → 작업
 * 공간 저장기가 1초 안에 저장한다. 같은 값이면 아무 일도 없다.
 */
public interface RecordWindowStateUseCase {

    /** 기다리지 않고 돌아온다. 어느 스레드에서 불러도 된다. 응답은 상태에 넣은 뒤 완료된다. */
    CompletionStage<Void> record(WindowState state);
}
