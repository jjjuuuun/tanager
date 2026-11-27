package tanager.application.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigError;

/**
 * 설정을 읽지 못했을 때의 명령(ADR-0009 개정). 다시 읽다 실패하면 마지막 정상 설정을 그대로 쓰고 알린다. 처음 읽다 실패하면 기본값으로
 * {@link AppEvent.SettingsChanged} 를 한 번 내고 기본 이력 규칙을 반영한다({@link PolicyChanged}) — 읽지 못한 이유는 셸 시작 알림({@code StartNotice.ConfigUnreadable})이 보인다. 응답은
 * 설정을 바꿨는가.
 */
public record SettingsLoadFailed(ConfigError error) implements Command<Boolean> {
    public SettingsLoadFailed {
        Objects.requireNonNull(error, "error");
    }

    @Override
    public Transition<Boolean> apply(AppState current) {
        if (current.settings().isEmpty()) {
            AppConfig defaults = AppConfig.defaults();
            Transition<Boolean> policy =
                    new PolicyChanged(PolicyChanged.policyOf(defaults.history())).apply(current.withSettings(defaults));
            List<AppEvent> events = new ArrayList<>();
            events.add(new AppEvent.SettingsChanged(defaults));
            events.addAll(policy.events());
            return new Transition<>(policy.next(), events, true);
        }
        return new Transition<>(
                current, List.of(new AppEvent.SettingsNotice(new AppEvent.SettingsProblem.KeptPrevious(error))), false);
    }
}
