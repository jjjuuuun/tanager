package dev.tanager.application.port.in;

import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.WorkingDir;
import java.util.Objects;
import java.util.Optional;

/**
 * 세션 시작 요청.
 *
 * @param profile 띄울 프로파일. 비면 설정의 기본 프로파일 → 기본 규칙
 * @param dir 시작 경로. 비면 그 프로파일의 설정 시작 경로 → 홈
 * @param size 처음 PTY 크기
 */
public record StartRequest(Optional<ProfileId> profile, Optional<WorkingDir> dir, TerminalSize size) {
    public StartRequest {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(size, "size");
    }
}
