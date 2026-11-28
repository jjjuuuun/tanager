package tanager.application.port.out;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.session.ProfileId;

/**
 * 사용자 설정(config.json, SDD §9.3) 중 앱이 아는 부분. 모르는 키는 이 값에 담지 않고 저장소가 파일에서 보존한다(ADR-0009).
 *
 * @param defaultProfile 시작할 때 띄울 프로파일
 * @param profiles 프로파일별 설정
 * @param openPlacement 트리에서 연 창의 자리
 * @param appearance 테마 · 전환 효과
 * @param terminal 글꼴 · 크기 · 줄 높이 · 스크롤백
 * @param history 이력 한도 · 제외 경로
 * @param monitor 리소스 표시
 * @param keys prefix 키 · 바꾼 바인딩
 * @param toolkit 도구함 공급자 · 대화 가져오기
 */
public record AppConfig(
        Optional<ProfileId> defaultProfile,
        Map<ProfileId, ProfileSettings> profiles,
        OpenPlacement openPlacement,
        AppearanceConfig appearance,
        TerminalConfig terminal,
        HistoryConfig history,
        MonitorConfig monitor,
        KeyConfig keys,
        ToolkitConfig toolkit) {
    private static final AppConfig DEFAULTS = new AppConfig(Optional.empty(), Map.of());

    public AppConfig {
        Objects.requireNonNull(defaultProfile, "defaultProfile");
        profiles = Map.copyOf(profiles);
        Objects.requireNonNull(openPlacement, "openPlacement");
        Objects.requireNonNull(appearance, "appearance");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(monitor, "monitor");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(toolkit, "toolkit");
    }

    /** 도구함은 기본값인 설정. */
    public AppConfig(
            Optional<ProfileId> defaultProfile,
            Map<ProfileId, ProfileSettings> profiles,
            OpenPlacement openPlacement,
            AppearanceConfig appearance,
            TerminalConfig terminal,
            HistoryConfig history,
            MonitorConfig monitor,
            KeyConfig keys) {
        this(
                defaultProfile,
                profiles,
                openPlacement,
                appearance,
                terminal,
                history,
                monitor,
                keys,
                ToolkitConfig.defaults());
    }

    /** 프로파일 밖의 항목은 모두 기본값인 설정. */
    public AppConfig(
            Optional<ProfileId> defaultProfile, Map<ProfileId, ProfileSettings> profiles, OpenPlacement openPlacement) {
        this(
                defaultProfile,
                profiles,
                openPlacement,
                AppearanceConfig.defaults(),
                TerminalConfig.defaults(),
                HistoryConfig.defaults(),
                MonitorConfig.defaults(),
                KeyConfig.defaults());
    }

    /** 창 자리와 프로파일 밖의 항목은 기본값인 설정. */
    public AppConfig(Optional<ProfileId> defaultProfile, Map<ProfileId, ProfileSettings> profiles) {
        this(defaultProfile, profiles, OpenPlacement.RIGHT);
    }

    /** 설정 파일이 없을 때의 값: 기본 프로파일도 프로파일별 설정도 없고 나머지는 SDD §9.3 의 기본값이다. */
    public static AppConfig defaults() {
        return DEFAULTS;
    }

    /** {@code id} 프로파일에 지정한 시작 경로 원문. */
    public Optional<String> startDirOf(ProfileId id) {
        return Optional.ofNullable(profiles.get(id)).flatMap(ProfileSettings::startDir);
    }

    /** 기본 프로파일만 바꾼 새 설정. */
    public AppConfig withDefaultProfile(ProfileId id) {
        return new AppConfig(
                Optional.of(id), profiles, openPlacement, appearance, terminal, history, monitor, keys, toolkit);
    }

    /** 저장소가 쓰는 항목(기본 프로파일 · 프로파일별 설정)만 {@code saved} 의 값으로 바꾼 새 설정(ADR-0009). */
    public AppConfig withSavedKeysOf(AppConfig saved) {
        return new AppConfig(
                saved.defaultProfile,
                saved.profiles,
                openPlacement,
                appearance,
                terminal,
                history,
                monitor,
                keys,
                toolkit);
    }
}
