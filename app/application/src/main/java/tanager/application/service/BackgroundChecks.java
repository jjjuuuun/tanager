package tanager.application.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tanager.domain.history.SessionLeaf;
import tanager.domain.history.SessionStatus;
import tanager.domain.history.ShellEnvironment;

/**
 * 백그라운드 확인 · 읽기({@link MissingPathScanner} · {@link ConversationImporter})가 같이 쓰는 판정. WSL 은 실행 중 세션이 있는
 * 배포판만 건드린다 — {@code \\wsl.localhost} 를 열면 VM 이 깨기 때문이다(FR-MON-02).
 */
final class BackgroundChecks {
    private BackgroundChecks() {}

    /** 실행 중 세션이 있는 WSL 배포판(세션 순서). */
    static Set<ShellEnvironment> awakeDistros(List<SessionLeaf> sessions) {
        Set<ShellEnvironment> awake = new LinkedHashSet<>();
        for (SessionLeaf s : sessions) {
            if (s.env() instanceof ShellEnvironment.Wsl && s.status() instanceof SessionStatus.Running) {
                awake.add(s.env());
            }
        }
        return awake;
    }

    /** 한 적 없으면 늘, {@code staleAfter} 가 있으면 마지막으로 한 뒤 그만큼 지났을 때도. */
    static boolean due(Optional<Instant> last, Optional<Duration> staleAfter, Instant now) {
        return last.isEmpty()
                || staleAfter.map(d -> !last.get().plus(d).isAfter(now)).orElse(false);
    }
}
