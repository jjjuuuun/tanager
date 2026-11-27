package tanager.application.port.out;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.shared.Result;

/**
 * {@link ConfigRepository} 의 수작업 Fake. 기본은 메모리 저장소이고 JSON 구현처럼 {@code save} 는 기본 프로파일 · 프로파일별 설정만
 * 바꾼다. 테스트가 {@code load} 결과나
 * {@code save} 실패를 지정할 수 있다. 호출을 기록한다. 스레드 안전.
 */
public final class InMemoryConfigRepository implements ConfigRepository {
    private AppConfig stored = AppConfig.defaults();
    private Optional<Result<LoadedConfig, ConfigError>> loadOverride = Optional.empty();
    private Optional<ConfigError> saveFailure = Optional.empty();
    private final List<AppConfig> saves = new ArrayList<>();
    private Optional<RuntimeException> loadThrows = Optional.empty();
    private int loadCalls;
    private int ensureCalls;

    /** {@link #ensureFile()} 가 돌려주는 위치(실제 파일은 없다). */
    public Path location() {
        return Path.of("in-memory", "config.json");
    }

    /** 앞으로의 {@code load} 가 이 결과를 돌려준다. */
    public synchronized InMemoryConfigRepository willLoad(Result<LoadedConfig, ConfigError> result) {
        this.loadOverride = Optional.of(Objects.requireNonNull(result));
        this.loadThrows = Optional.empty();
        return this;
    }

    /** 앞으로의 {@code load} 가 이 오류로 실패한다. */
    public synchronized InMemoryConfigRepository willFailLoad(ConfigError error) {
        return willLoad(Result.err(error));
    }

    /** 앞으로의 {@code load} 가 이 예외를 던진다(버그 흉내). {@link #willLoad} 를 부르면 풀린다. */
    public synchronized InMemoryConfigRepository willThrowOnLoad(RuntimeException exception) {
        this.loadThrows = Optional.of(Objects.requireNonNull(exception));
        return this;
    }

    /** 앞으로의 {@code save} 가 이 오류로 실패한다(저장하지 않는다). */
    public synchronized InMemoryConfigRepository willFailSave(ConfigError error) {
        this.saveFailure = Optional.of(Objects.requireNonNull(error));
        return this;
    }

    @Override
    public synchronized Result<LoadedConfig, ConfigError> load() {
        loadCalls++;
        if (loadThrows.isPresent()) {
            throw loadThrows.get();
        }
        return loadOverride.orElseGet(() -> Result.ok(new LoadedConfig(stored, List.of())));
    }

    @Override
    public synchronized Result<AppConfig, ConfigError> save(AppConfig config) {
        saves.add(config);
        if (saveFailure.isPresent()) {
            return Result.err(saveFailure.get());
        }
        stored = stored.withSavedKeysOf(config);
        return Result.ok(config);
    }

    @Override
    public synchronized Result<Path, ConfigError> ensureFile() {
        ensureCalls++;
        return Result.ok(location());
    }

    /** {@code save} 에 넘어온 값들(실패한 호출 포함, 불변 사본). */
    public synchronized List<AppConfig> saves() {
        return List.copyOf(saves);
    }

    public synchronized int loadCalls() {
        return loadCalls;
    }

    public synchronized int ensureCalls() {
        return ensureCalls;
    }
}
