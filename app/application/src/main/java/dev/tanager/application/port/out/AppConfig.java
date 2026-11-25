package dev.tanager.application.port.out;

import dev.tanager.domain.session.ProfileId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 사용자 설정(config.json, SDD §9.3) 중 앱이 아는 부분. 모르는 키는 이 값에 담지 않고 저장소가 파일에서 보존한다(ADR-0009).
 *
 * @param defaultProfile 시작할 때 띄울 프로파일
 * @param profiles 프로파일별 설정
 */
public record AppConfig(Optional<ProfileId> defaultProfile, Map<ProfileId, ProfileSettings> profiles) {
    private static final AppConfig DEFAULTS = new AppConfig(Optional.empty(), Map.of());

    public AppConfig {
        Objects.requireNonNull(defaultProfile, "defaultProfile");
        profiles = Map.copyOf(profiles);
    }

    /** 설정 파일이 없을 때의 값: 기본 프로파일도 프로파일별 설정도 없다. */
    public static AppConfig defaults() {
        return DEFAULTS;
    }

    /** {@code id} 프로파일에 지정한 시작 경로 원문. */
    public Optional<String> startDirOf(ProfileId id) {
        return Optional.ofNullable(profiles.get(id)).flatMap(ProfileSettings::startDir);
    }
}
