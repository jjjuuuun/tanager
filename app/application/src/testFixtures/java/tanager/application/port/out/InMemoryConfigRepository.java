package tanager.application.port.out;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.shared.Result;

/**
 * {@link ConfigRepository} 의 수작업 Fake. 기본은 저장한 값을 그대로 돌려주는 메모리 저장소이고, 테스트가 {@code load} 결과나
 * {@code save} 실패를 지정할 수 있다. 호출을 기록한다. 스레드 안전.
 */
public final class InMemoryConfigRepository implements ConfigRepository {
    private AppConfig stored = AppConfig.defaults();
    private Optional<Result<LoadedConfig, ConfigError>> loadOverride = Optional.empty();
    private Optional<ConfigError> saveFailure = Optional.empty();
    private final List<AppConfig> saves = new ArrayList<>();
    private int loadCalls;

    /** 앞으로의 {@code load} 가 이 결과를 돌려준다. */
    public synchronized InMemoryConfigRepository willLoad(Result<LoadedConfig, ConfigError> result) {
        this.loadOverride = Optional.of(Objects.requireNonNull(result));
        return this;
    }

    /** 앞으로의 {@code load} 가 이 오류로 실패한다. */
    public synchronized InMemoryConfigRepository willFailLoad(ConfigError error) {
        return willLoad(Result.err(error));
    }

    /** 앞으로의 {@code save} 가 이 오류로 실패한다(저장하지 않는다). */
    public synchronized InMemoryConfigRepository willFailSave(ConfigError error) {
        this.saveFailure = Optional.of(Objects.requireNonNull(error));
        return this;
    }

    @Override
    public synchronized Result<LoadedConfig, ConfigError> load() {
        loadCalls++;
        return loadOverride.orElseGet(() -> Result.ok(new LoadedConfig(stored, List.of())));
    }

    @Override
    public synchronized Result<AppConfig, ConfigError> save(AppConfig config) {
        saves.add(config);
        if (saveFailure.isPresent()) {
            return Result.err(saveFailure.get());
        }
        stored = config;
        return Result.ok(config);
    }

    /** {@code save} 에 넘어온 값들(실패한 호출 포함, 불변 사본). */
    public synchronized List<AppConfig> saves() {
        return List.copyOf(saves);
    }

    public synchronized int loadCalls() {
        return loadCalls;
    }
}
