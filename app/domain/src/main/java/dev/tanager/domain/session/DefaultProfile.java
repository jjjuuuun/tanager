package dev.tanager.domain.session;

import dev.tanager.domain.history.ShellEnvironment;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 시작할 때 띄울 프로파일을 정하는 규칙(FR-SES-02): 선호 프로파일 → 첫 WSL → 목록의 첫 항목. */
public final class DefaultProfile {
    private DefaultProfile() {}

    /**
     * {@code available} 에서 띄울 프로파일을 고른다. 선호가 목록에 있으면 그것이고, 없으면 첫 WSL, WSL 도 없으면 첫 항목이다.
     * 선호를 찾지 못해 다른 것을 골랐으면 {@link ProfileChoice#replaced()} 에 선호 id 를 담는다. 목록이 비면 비어 있다.
     */
    public static Optional<ProfileChoice> choose(Optional<ProfileId> preferred, List<ShellProfile> available) {
        Objects.requireNonNull(preferred, "preferred");
        Objects.requireNonNull(available, "available");
        Optional<ShellProfile> wanted = preferred.flatMap(id -> byId(id, available));
        if (wanted.isPresent()) {
            return Optional.of(new ProfileChoice(wanted.get(), Optional.empty()));
        }
        return fallback(available).map(profile -> new ProfileChoice(profile, preferred));
    }

    private static Optional<ShellProfile> byId(ProfileId id, List<ShellProfile> available) {
        return available.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    private static Optional<ShellProfile> fallback(List<ShellProfile> available) {
        Optional<ShellProfile> wsl = available.stream()
                .filter(p -> p.env() instanceof ShellEnvironment.Wsl)
                .findFirst();
        return wsl.or(() -> available.stream().findFirst());
    }
}
