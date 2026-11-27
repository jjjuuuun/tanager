package tanager.application.port.out;

import java.nio.file.Path;
import tanager.domain.shared.Result;

/** 사용자 설정(config.json)을 읽고 쓰는 출력 포트(ADR-0009). 읽기는 관대하게, 쓰기는 보수적으로. */
public interface ConfigRepository {

    /** 설정을 읽는다. 파일이 없으면 {@link AppConfig#defaults()} 이고 오류가 아니다. 파일을 바꾸지 않는다. */
    Result<LoadedConfig, ConfigError> load();

    /**
     * 아는 키 중 기본 프로파일 · 프로파일별 시작 경로만 덮어써 저장한다. 나머지 항목(창 자리 · 외관 · 터미널 · 이력 · 리소스 · 키)과 파일의 모르는
     * 키는 저장소의 값을 그대로 둔다 — 쓰면 사용자의 주석이 사라지기 때문이다(ADR-0009). 디스크의 파일이 손상됐거나 상위 버전(또는 버전을 해석할 수 없음)이면 쓰지 않고
     * {@link ConfigError.WouldOverwrite} 를 돌려준다.
     */
    Result<AppConfig, ConfigError> save(AppConfig config);

    /**
     * 사용자가 편집기로 열 설정 파일. 없으면 {@code {"schemaVersion": 1}} 만 담아 만들고(우리 파일이라 써도 된다), 있으면(빈 파일 포함)
     * 건드리지 않는다. 만들지 못하면 {@link ConfigError.WriteFailed}.
     */
    Result<Path, ConfigError> ensureFile();
}
