package tanager.application.service;

import java.nio.file.Path;
import java.util.Objects;
import tanager.application.port.in.SettingsUseCase;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigError;
import tanager.application.port.out.ConfigRepository;
import tanager.application.port.out.ConfigWatch;
import tanager.application.port.out.LoadedConfig;
import tanager.application.port.out.Subscription;
import tanager.application.state.Command;
import tanager.application.state.SettingsLoadFailed;
import tanager.application.state.SettingsLoaded;
import tanager.application.state.StateActor;
import tanager.domain.shared.Result;

/**
 * 설정 파일이 바뀌면 다시 읽어 상태에 반영한다(FR-CFG-03, ADR-0009 개정). 시작할 때도 같은 길로 한 번 읽어 {@code SettingsChanged} 가 한 번
 * 나간다 — UI 는 그 이벤트만 본다.
 */
public final class SettingsReloader implements SettingsUseCase {
    private static final System.Logger LOG = System.getLogger(SettingsReloader.class.getName());

    private final ConfigRepository config;
    private final StateActor state;
    // 읽기와 제출을 한 덩어리로 — 감시 콜백이 겹쳐도 나중에 읽은 값이 나중에 반영된다
    private final Object reloading = new Object();

    public SettingsReloader(ConfigRepository config, StateActor state) {
        this.config = Objects.requireNonNull(config, "config");
        this.state = Objects.requireNonNull(state, "state");
    }

    /** 감시를 건 뒤 한 번 읽는다(그사이의 변경을 놓치지 않게). 돌려준 구독을 끊으면 더 읽지 않는다. 부른 스레드에서 파일을 읽는다. */
    public Subscription start(ConfigWatch watch) {
        Subscription subscription = Objects.requireNonNull(watch, "watch").watch(this::reload);
        reload();
        return subscription;
    }

    /** 설정을 읽어 상태에 넘긴다. 던지지 않는다. */
    void reload() {
        synchronized (reloading) {
            state.submit(readCommand());
        }
    }

    private Command<Boolean> readCommand() {
        try {
            return switch (config.load()) {
                case Result.Ok<LoadedConfig, ConfigError>(LoadedConfig loaded) -> new SettingsLoaded(loaded);
                case Result.Err<LoadedConfig, ConfigError>(ConfigError error) -> new SettingsLoadFailed(error);
            };
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "설정 다시 읽기 실패", e);
            return new SettingsLoadFailed(new ConfigError.Unreadable(e.toString()));
        }
    }

    @Override
    public AppConfig current() {
        return state.snapshot().settings().orElseGet(AppConfig::defaults);
    }

    @Override
    public Result<Path, ConfigError> file() {
        return config.ensureFile();
    }
}
