package tanager.application.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.HistoryConfig;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.PathPattern;
import tanager.domain.history.PatternProblem;
import tanager.domain.history.WorkHistory;
import tanager.domain.shared.Result;

/**
 * 이력 규칙(한도 · 제외 glob)을 바꾸는 명령(FR-HIS-09, #186). 설정을 반영하는 명령({@link SettingsLoaded} · {@link
 * SettingsLoadFailed})이 같은 전이 안에서 부른다. 항목이 바뀌었을 때만 {@link AppEvent.HistoryChanged} 를 낸다 — 그 이벤트로 1초 안에
 * 저장된다. 정책만 바뀌면 저장할 것이 없다. 응답은 정책을 바꿨는가.
 */
public record PolicyChanged(HistoryPolicy policy) implements Command<Boolean> {
    private static final System.Logger LOG = System.getLogger(PolicyChanged.class.getName());

    public PolicyChanged {
        Objects.requireNonNull(policy, "policy");
    }

    /**
     * 설정의 이력 항목을 정책으로 바꾼다. glob 으로 읽을 수 없는 제외 규칙은 그것만 버린다 — 설정 저장소가 읽을 때 이미 걸러 알렸다(
     * {@code ConfigWarning}).
     */
    public static HistoryPolicy policyOf(HistoryConfig config) {
        Objects.requireNonNull(config, "config");
        List<PathPattern> exclude = new ArrayList<>();
        for (String raw : config.exclude()) {
            switch (PathPattern.of(raw)) {
                case Result.Ok<PathPattern, PatternProblem>(PathPattern pattern) -> exclude.add(pattern);
                case Result.Err<PathPattern, PatternProblem>(PatternProblem problem) ->
                    LOG.log(System.Logger.Level.WARNING, "이력 제외 규칙을 버린다: {0} ({1})", raw, problem);
            }
        }
        return new HistoryPolicy(config.maxEntries(), exclude);
    }

    /**
     * {@code config} 를 설정으로 두고 그 이력 규칙을 같은 전이에서 반영한다. 이벤트는 {@code SettingsChanged} 뒤에 정책 반영의 이벤트다.
     * 응답은 늘 {@code true}(설정을 바꿨다).
     */
    static Transition<Boolean> settingsApplied(AppState current, AppConfig config) {
        Transition<Boolean> policy = new PolicyChanged(policyOf(config.history())).apply(current.withSettings(config));
        List<AppEvent> events = new ArrayList<>();
        events.add(new AppEvent.SettingsChanged(config));
        events.addAll(policy.events());
        return new Transition<>(policy.next(), events, true);
    }

    @Override
    public Transition<Boolean> apply(AppState current) {
        WorkHistory history = current.history();
        if (history.policy().equals(policy)) {
            return new Transition<>(current, List.of(), false);
        }
        WorkHistory next = history.withPolicy(policy);
        List<AppEvent> events =
                next.entries().equals(history.entries()) ? List.of() : List.of(new AppEvent.HistoryChanged(next));
        return new Transition<>(current.withHistory(next), events, true);
    }
}
