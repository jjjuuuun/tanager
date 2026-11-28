package tanager.application.port.in;

/**
 * AI 도구의 이전 대화 가져오기(FR-TK-09, #215). 결과는 {@link AppEvent.ConversationsChanged} · {@link AppEvent.HistoryChanged}
 * · {@link AppEvent.ImportNotice} 로 온다.
 */
public interface ImportConversationsUseCase {

    /** 창이 활성화됐다 — 마지막 읽기에서 60초가 지난 환경을 백그라운드로 다시 읽는다. 바로 돌아온다. EDT 에서 불러도 된다. */
    void refresh();
}
