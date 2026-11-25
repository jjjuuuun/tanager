package dev.tanager.domain.session;

import java.util.Objects;
import java.util.Optional;

/**
 * 띄울 프로파일 결정. {@code replaced} 는 선호한 프로파일이 없어서 대신 {@code profile} 을 골랐을 때 그 선호 id 다.
 *
 * @param profile 고른 프로파일
 * @param replaced 대체된 선호 id — 있으면 {@code profile.id()} 와 다르다
 */
public record ProfileChoice(ShellProfile profile, Optional<ProfileId> replaced) {
    public ProfileChoice {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(replaced, "replaced");
        if (replaced.filter(profile.id()::equals).isPresent()) {
            throw new IllegalArgumentException("대체한 id 가 고른 프로파일과 같다: " + profile.id());
        }
    }
}
