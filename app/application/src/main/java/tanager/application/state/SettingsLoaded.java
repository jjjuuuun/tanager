package tanager.application.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.application.port.in.AppEvent;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigWarning;
import tanager.application.port.out.LoadedConfig;

/**
 * 설정을 읽었을 때의 명령(FR-CFG-03, ADR-0009 개정). 값이 바뀌었을 때만(처음 읽을 때는 늘) 설정을 바꾸고 {@link AppEvent.SettingsChanged}
 * 를 낸다. 응답은 바꿨는가.
 *
 * <p>경고는 다시 읽을 때만 알린다. 시작 때의 경고는 셸 시작 알림({@code StartNotice.ConfigWarned})이 이미 보인다.
 */
public record SettingsLoaded(LoadedConfig loaded) implements Command<Boolean> {
    public SettingsLoaded {
        Objects.requireNonNull(loaded, "loaded");
    }

    @Override
    public Transition<Boolean> apply(AppState current) {
        Optional<AppConfig> previous = current.settings();
        AppConfig next = loaded.config();
        if (previous.equals(Optional.of(next))) {
            return new Transition<>(current, List.of(), false);
        }
        List<AppEvent> events = new ArrayList<>();
        events.add(new AppEvent.SettingsChanged(next));
        if (previous.isPresent()) {
            for (ConfigWarning warning : loaded.warnings()) {
                events.add(new AppEvent.SettingsNotice(new AppEvent.SettingsProblem.Warned(warning)));
            }
        }
        return new Transition<>(current.withSettings(next), events, true);
    }
}
