package dev.tanager.application.port.out;

import java.util.List;
import java.util.Objects;

/**
 * 읽은 설정과 읽는 동안 난 경고.
 *
 * @param config 읽은 값(형식이 틀린 키는 기본값)
 * @param warnings 무시한 값 · 상위 버전 등
 */
public record LoadedConfig(AppConfig config, List<ConfigWarning> warnings) {
    public LoadedConfig {
        Objects.requireNonNull(config, "config");
        warnings = List.copyOf(warnings);
    }
}
