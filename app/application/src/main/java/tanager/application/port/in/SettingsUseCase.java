package tanager.application.port.in;

import java.nio.file.Path;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigError;
import tanager.domain.shared.Result;

/** 지금 설정과 설정 파일 위치(FR-CFG-03). UI 의 "설정 열기"가 쓴다. 설정이 바뀌면 {@link AppEvent.SettingsChanged} 가 나간다. */
public interface SettingsUseCase {

    /** 마지막으로 읽은 정상 설정. 아직 읽기 전이면 기본값. */
    AppConfig current();

    /** 편집기로 열 설정 파일. 없으면 {@code {"schemaVersion": 1}} 만 담아 만든다. 만들지 못하면 그 이유. */
    Result<Path, ConfigError> file();
}
