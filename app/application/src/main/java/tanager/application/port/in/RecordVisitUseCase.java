package tanager.application.port.in;

import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.PaneId;

/** 창의 셸이 알린 현재 경로를 이력에 기록한다(FR-HIS-01). 그 창이 마지막으로 알린 경로와 같으면 아무 일도 없다. */
public interface RecordVisitUseCase {

    /** 기다리지 않고 돌아온다 — PTY 읽기 스레드에서 부른다. 어느 스레드에서 불러도 된다. */
    void record(PaneId pane, WorkspacePath cwd);
}
