package dev.tanager.application.port.out;

import java.util.Objects;
import java.util.Optional;

/**
 * 프로파일 하나의 사용자 설정(config.json 의 {@code profiles.<id>}).
 *
 * @param startDir 시작 경로 원문. {@code "~"} 는 홈이다. 빈 문자열은 받지 않는다
 */
public record ProfileSettings(Optional<String> startDir) {
    public ProfileSettings {
        Objects.requireNonNull(startDir, "startDir");
        if (startDir.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("시작 경로가 비어 있다");
        }
    }
}
