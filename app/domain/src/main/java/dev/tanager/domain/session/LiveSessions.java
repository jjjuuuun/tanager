package dev.tanager.domain.session;

import dev.tanager.domain.history.SessionLeaf;
import dev.tanager.domain.history.WorkspacePath;
import dev.tanager.domain.shared.PaneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 창별 세션과 표시 번호("#1 bash")의 불변 모음. 번호는 처음 띄운 순서대로 주고, 창을 지워도 다시 쓰지 않는다 — 트리에서 본 "#2"
 * 가 다른 세션을 가리키지 않게. 바꾸는 메서드는 새 값을 돌려준다.
 *
 * <p>불변식: 키는 그 값의 창 · 번호는 창마다 다르다 · 모든 번호 &lt; nextNumber · nextNumber ≥ 1.
 */
public record LiveSessions(Map<PaneId, LiveSession> byPane, int nextNumber) {

    private static final LiveSessions EMPTY = new LiveSessions(Map.of(), 1);

    public LiveSessions {
        byPane = Map.copyOf(byPane);
        if (nextNumber < 1) {
            throw new IllegalArgumentException("다음 번호는 1 이상이어야 한다: " + nextNumber);
        }
        Set<Integer> numbers = new HashSet<>();
        for (Map.Entry<PaneId, LiveSession> entry : byPane.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().pane())) {
                throw new IllegalArgumentException("키와 세션의 창이 다르다: " + entry.getKey());
            }
            if (entry.getValue().number() >= nextNumber) {
                throw new IllegalArgumentException(
                        "번호가 다음 번호보다 작아야 한다: " + entry.getValue().number());
            }
            if (!numbers.add(entry.getValue().number())) {
                throw new IllegalArgumentException(
                        "두 창이 같은 번호를 가진다: " + entry.getValue().number());
            }
        }
    }

    /** 창이 하나도 없다. */
    public static LiveSessions empty() {
        return EMPTY;
    }

    /**
     * {@code pane} 에 셸이 떴다. 처음 띄운 창은 새 번호를 받고, 다시 띄운 창은 번호를 유지한다. 어느 쪽이든 마지막으로 알린 경로는
     * 비운다 — 새 셸의 첫 보고는 다시 기록한다.
     *
     * @throws IllegalArgumentException {@code running} 이 Running 이 아닐 때
     */
    public LiveSessions launched(PaneId pane, TerminalSession running) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(running, "running");
        if (!(running.state() instanceof SessionState.Running)) {
            throw new IllegalArgumentException("띄운 세션은 Running 이어야 한다: " + running.state());
        }
        Optional<LiveSession> existing = Optional.ofNullable(byPane.get(pane));
        int number = existing.map(LiveSession::number).orElse(nextNumber);
        int next = existing.isPresent() ? nextNumber : nextNumber + 1;
        return new LiveSessions(put(new LiveSession(pane, number, running, Optional.empty())), next);
    }

    /**
     * {@code pane} 의 셸이 {@code path} 를 알렸다. 변화가 있으면 세션의 현재 경로와 마지막으로 알린 경로를 바꾼 값, 없으면 빈 값이다.
     * 변화 없음: 마지막으로 알린 경로와 같다 · 모르는 창 · Running 이 아니다 · 세션과 다른 환경의 경로.
     */
    public Optional<LiveSessions> cwdReported(PaneId pane, WorkspacePath path) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(path, "path");
        return Optional.ofNullable(byPane.get(pane))
                .filter(live -> !live.lastReported().equals(Optional.of(path)))
                .flatMap(live -> live.session()
                        .cwdChanged(path)
                        .value()
                        .map(moved -> replaced(new LiveSession(pane, live.number(), moved, Optional.of(path)))));
    }

    /** {@code pane} 의 셸이 {@code exitCode} 로 끝났다. 모르는 창 · 이미 끝난 창이면 그대로다. 마지막 경로는 유지한다. */
    public LiveSessions exited(PaneId pane, int exitCode) {
        Objects.requireNonNull(pane, "pane");
        return Optional.ofNullable(byPane.get(pane))
                .flatMap(live -> live.session()
                        .exited(exitCode)
                        .value()
                        .map(ended -> replaced(new LiveSession(pane, live.number(), ended, live.lastReported()))))
                .orElse(this);
    }

    /** 창이 닫혔다. 그 번호는 다시 쓰지 않는다. */
    public LiveSessions removed(PaneId pane) {
        Objects.requireNonNull(pane, "pane");
        if (!byPane.containsKey(pane)) {
            return this;
        }
        Map<PaneId, LiveSession> next = new HashMap<>(byPane);
        next.remove(pane);
        return new LiveSessions(next, nextNumber);
    }

    /** 트리에 매달 세션들(번호 순, 수정 불가). */
    public List<SessionLeaf> leaves() {
        return byPane.values().stream()
                .sorted(Comparator.comparingInt(LiveSession::number))
                .map(LiveSession::leaf)
                .toList();
    }

    // 번호를 새로 주지 않고 그 창의 세션만 바꾼다
    private LiveSessions replaced(LiveSession session) {
        return new LiveSessions(put(session), nextNumber);
    }

    private Map<PaneId, LiveSession> put(LiveSession session) {
        Map<PaneId, LiveSession> next = new HashMap<>(byPane);
        next.put(session.pane(), session);
        return next;
    }
}
