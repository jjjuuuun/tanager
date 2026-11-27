package tanager.application.port.in;

/** 사라진 이력 경로 확인(FR-HIS-08, #186). 결과는 {@link AppEvent.MissingPathsChanged} 로 온다. */
public interface CheckMissingPathsUseCase {

    /** 창이 활성화됐다 — 마지막 확인에서 5분이 지난 항목을 백그라운드로 다시 확인한다. 바로 돌아온다. EDT 에서 불러도 된다. */
    void windowActivated();
}
