package dev.tanager.domain.session;

import dev.tanager.domain.history.SessionLeaf;
import dev.tanager.domain.history.SessionStatus;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.shared.PaneId;
import java.util.Objects;
import java.util.Optional;

/**
 * 창 하나에 떠 있는(또는 끝났지만 창이 남은) 세션. {@code lastReported} 는 이 창의 셸 통합이 마지막으로 알린 경로로, 같은 경로가
 * 프롬프트마다 와도 한 번만 기록하는 기준이다(ADR-0015). 시작 경로와는 다르다 — 시작 폴더도 첫 보고 때 기록된다.
 *
 * <p>불변식: number ≥ 1 · 세션은 Running 또는 Exited · lastReported 가 있으면 그 환경이 세션 환경.
 */
public record LiveSession(PaneId pane, int number, TerminalSession session, Optional<WorkspacePath> lastReported) {

    public LiveSession {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(lastReported, "lastReported");
        if (number < 1) {
            throw new IllegalArgumentException("세션 번호는 1 이상이어야 한다: " + number);
        }
        if (!(session.state() instanceof SessionState.Running || session.state() instanceof SessionState.Exited)) {
            throw new IllegalArgumentException("창의 세션은 Running 또는 Exited 여야 한다: " + session.state());
        }
        lastReported.ifPresent(path -> {
            if (!path.env().equals(session.profile().env())) {
                throw new IllegalArgumentException("마지막으로 알린 경로의 환경이 세션과 다르다: " + path + " ≠ "
                        + session.profile().env());
            }
        });
    }

    /** 트리에 매달 값. 홈은 아직 모르는 경로라 비어 있고, 끝난 세션은 마지막 경로를 가진다. */
    public SessionLeaf leaf() {
        ShellProfile profile = session.profile();
        return new SessionLeaf(pane, number, profile.name(), profile.env(), session.knownCwd(), status());
    }

    private SessionStatus status() {
        return switch (session.state()) {
            case SessionState.Running _ -> new SessionStatus.Running();
            case SessionState.Exited e -> new SessionStatus.Exited(e.exitCode());
            case SessionState.Starting _, SessionState.Failed _ ->
                throw new IllegalStateException("불변식 위반: " + session.state());
        };
    }
}
