package tanager.application.port.in;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.toolkit.ProviderId;
import tanager.domain.toolkit.SourceRef;
import tanager.domain.toolkit.ToolkitView;

/** 한 경로의 도구함(FR-TK-01 · FR-TK-08 · NFR-07, SDD §8.1, ADR-0019). 도구함 패널이 쓴다. */
public interface QueryToolkitUseCase {

    /** 지난 결과를 다시 쓸지. */
    enum Freshness {
        /** 공급자의 지문(파일 수정 시각)이 같으면 지난 결과를 쓴다 — 선택 · 창 포커스 때. */
        REUSE_IF_UNCHANGED,
        /** 늘 다시 읽는다 — 새로고침 버튼. */
        FORCE
    }

    /**
     * {@code path} 의 도구함. 부른 스레드에서 읽지 않는다 — I/O 실행기에서 읽고 완료한다(EDT · {@code StateActor} 를 막지 않는다. 상태가 아니므로
     * 액터를 거치지 않는다). 공급자가 실패해도 예외로 끝나지 않는다(앱 종료 중에 부르면 예외로 끝날 수 있다).
     *
     * <p>설정 {@code toolkit.providers}(비면 모두) 중 {@code supports(path.env)} 인 공급자를 모두 부르고 결과를 합친다. 공급자 하나가 실패하거나
     * 10초를 넘기면 그 공급자만 {@code ProviderFailed} 경고. 홈을 모르면 {@code HomeUnknown} 경고 + 프로젝트 범위만. 같은 path 의 조회가
     * 진행 중이면 그 결과를 같이 받는다.
     */
    CompletionStage<ToolkitView> query(WorkspacePath path, Freshness freshness);

    /** 그 환경에 쓸 수 있는 공급자(칩, 등록 순). 상태를 바꾸지 않는다. */
    List<ProviderId> providers(ShellEnvironment env);

    /** 항목 원본을 OS 편집기로 열 경로(파일 열기, FR-TK-06). 순수 — 파일시스템을 보지 않는다. 열 수 없으면 empty. */
    Optional<Path> hostFile(SourceRef source);
}
