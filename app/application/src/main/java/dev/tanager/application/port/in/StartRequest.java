package dev.tanager.application.port.in;

import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.WorkingDir;
import java.util.Objects;
import java.util.Optional;

/**
 * 세션 시작 요청.
 *
 * @param profile 띄울 프로파일. 비면 설정의 기본 프로파일(기억한 셸)
 * @param rememberAsDefault 띄우는 데 성공하면 {@code profile} 을 기본 프로파일로 저장한다. 켜려면 {@code profile} 이 있어야 한다
 * @param dir 시작 경로. 비면 그 프로파일의 설정 시작 경로 → 홈
 * @param size 처음 PTY 크기
 */
public record StartRequest(
        Optional<ProfileId> profile, boolean rememberAsDefault, Optional<WorkingDir> dir, TerminalSize size) {
    public StartRequest {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(size, "size");
        if (rememberAsDefault && profile.isEmpty()) {
            throw new IllegalArgumentException("기억할 프로파일이 없다");
        }
    }

    /** 기억한 셸로 띄운다(없거나 사라졌으면 {@link StartFailure.ChoiceRequired}). */
    public static StartRequest remembered(TerminalSize size) {
        return new StartRequest(Optional.empty(), false, Optional.empty(), size);
    }

    /** 사용자가 고른 셸을 그 셸의 시작 경로에서 띄운다. */
    public static StartRequest chosen(ProfileId profile, boolean rememberAsDefault, TerminalSize size) {
        return new StartRequest(Optional.of(profile), rememberAsDefault, Optional.empty(), size);
    }
}
