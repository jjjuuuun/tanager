package dev.tanager.application.service;

import dev.tanager.application.port.in.RememberDefaultProfileUseCase;
import dev.tanager.application.port.in.Remembered;
import dev.tanager.application.port.out.AppConfig;
import dev.tanager.application.port.out.ConfigError;
import dev.tanager.application.port.out.ConfigRepository;
import dev.tanager.application.port.out.LoadedConfig;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.shared.Result;
import java.util.Objects;
import java.util.Optional;

/**
 * 기본 셸을 config.json 에 기억한다(FR-SES-02, ADR-0009 개정). 읽지 못한 설정을 기본값으로 덮어쓰면 프로파일별 설정이 지워지므로 그때는
 * 쓰지 않는다. 같은 값이면 쓰지 않아 파일의 주석이 남는다.
 */
public final class DefaultProfileMemory implements RememberDefaultProfileUseCase {
    private final ConfigRepository config;

    public DefaultProfileMemory(ConfigRepository config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Result<Remembered, ConfigError> remember(ProfileId profile) {
        Objects.requireNonNull(profile, "profile");
        return config.load().fold(loaded -> rememberOver(loaded, profile), Result::err);
    }

    private Result<Remembered, ConfigError> rememberOver(LoadedConfig loaded, ProfileId profile) {
        AppConfig current = loaded.config();
        if (current.defaultProfile().equals(Optional.of(profile))) {
            return Result.ok(Remembered.UNCHANGED);
        }
        return config.save(new AppConfig(Optional.of(profile), current.profiles()))
                .fold(saved -> Result.ok(Remembered.SAVED), Result::err);
    }
}
