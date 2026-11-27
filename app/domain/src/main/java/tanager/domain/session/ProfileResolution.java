package tanager.domain.session;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.history.ShellEnvironment;

/**
 * 시작할 때 띄울 프로파일을 정한 결과(FR-SES-02). 원하는 프로파일이 없거나 목록에 없으면 다른 셸로 바꾸지 않고 사용자에게 묻는다.
 */
public sealed interface ProfileResolution {

    /**
     * {@code wanted}(요청한 id, 없으면 기억한 기본 id)를 {@code available}(탐지 순서)에서 찾는다. 순수 함수. 목록이 비면 NoShell, 원하는 것이
     * 목록에 있으면 Found, 아니면 Choose(목록 그대로, 못 찾은 id).
     */
    static ProfileResolution resolve(Optional<ProfileId> wanted, List<ShellProfile> available) {
        Objects.requireNonNull(wanted, "wanted");
        Objects.requireNonNull(available, "available");
        if (available.isEmpty()) {
            return new NoShell();
        }
        for (ShellProfile profile : available) {
            if (wanted.filter(profile.id()::equals).isPresent()) {
                return new Found(profile);
            }
        }
        return new Choose(available, wanted);
    }

    /**
     * 경로 환경 {@code env} 에서 열 때 쓸 프로파일(#88, FR-HIS-05). 기억한 기본이 그 환경이고 목록에 있으면 그것, 아니면 그 환경의 탐지 순서 첫
     * 프로파일. 그 환경의 셸이 없으면 empty.
     */
    static Optional<ShellProfile> forEnvironment(
            ShellEnvironment env, Optional<ProfileId> remembered, List<ShellProfile> available) {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(remembered, "remembered");
        Objects.requireNonNull(available, "available");
        Optional<ShellProfile> first = Optional.empty();
        for (ShellProfile profile : available) {
            if (!profile.env().equals(env)) {
                continue;
            }
            if (remembered.filter(profile.id()::equals).isPresent()) {
                return Optional.of(profile);
            }
            if (first.isEmpty()) {
                first = Optional.of(profile);
            }
        }
        return first;
    }

    /** 원하는 프로파일이 목록에 있다 — 바로 띄운다. */
    record Found(ShellProfile profile) implements ProfileResolution {
        public Found {
            Objects.requireNonNull(profile, "profile");
        }
    }

    /**
     * 사용자가 골라야 한다.
     *
     * @param choices 고를 수 있는 프로파일, 탐지 순서 그대로
     * @param missing 원했지만 목록에 없던 id(기억한 셸이 사라짐). 처음 실행이면 비어 있다
     */
    record Choose(List<ShellProfile> choices, Optional<ProfileId> missing) implements ProfileResolution {
        public Choose {
            choices = List.copyOf(choices);
            Objects.requireNonNull(missing, "missing");
            if (choices.isEmpty()) {
                throw new IllegalArgumentException("고를 프로파일이 없다");
            }
            for (ShellProfile profile : choices) {
                if (missing.filter(profile.id()::equals).isPresent()) {
                    throw new IllegalArgumentException("사라진 id 가 목록에 있다: " + profile.id());
                }
            }
        }
    }

    /** 띄울 수 있는 셸이 하나도 없다. */
    record NoShell() implements ProfileResolution {}
}
