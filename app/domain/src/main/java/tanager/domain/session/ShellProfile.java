package tanager.domain.session;

import java.util.Objects;
import tanager.domain.history.ShellEnvironment;

/** 어떤 환경에서 어떤 셸을 어떤 인자로 띄울지. 셸 통합 방식도 함께 정한다. */
public record ShellProfile(
        ProfileId id,
        String name,
        ShellKind kind,
        ShellEnvironment env,
        LaunchTemplate launch,
        IntegrationMode integration) {
    public ShellProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(launch, "launch");
        Objects.requireNonNull(integration, "integration");
        if (name.isBlank()) {
            throw new IllegalArgumentException("프로파일 이름이 비어 있다");
        }
    }
}
