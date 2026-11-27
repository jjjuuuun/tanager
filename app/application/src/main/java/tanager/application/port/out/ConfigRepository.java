package tanager.application.port.out;

import tanager.domain.shared.Result;

/** 사용자 설정(config.json)을 읽고 쓰는 출력 포트(ADR-0009). 읽기는 관대하게, 쓰기는 보수적으로. */
public interface ConfigRepository {

    /** 설정을 읽는다. 파일이 없으면 {@link AppConfig#defaults()} 이고 오류가 아니다. 파일을 바꾸지 않는다. */
    Result<LoadedConfig, ConfigError> load();

    /**
     * 아는 키만 덮어써 저장한다. 파일의 모르는 키는 보존한다. 디스크의 파일이 손상됐거나 상위 버전(또는 버전을 해석할 수 없음)이면 쓰지 않고
     * {@link ConfigError.WouldOverwrite} 를 돌려준다.
     */
    Result<AppConfig, ConfigError> save(AppConfig config);
}
