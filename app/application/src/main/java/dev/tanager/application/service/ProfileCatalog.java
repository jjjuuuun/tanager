package dev.tanager.application.service;

import dev.tanager.application.port.in.ListProfilesUseCase;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.domain.session.ShellProfile;
import java.util.List;
import java.util.Objects;

/**
 * 플랫폼이 탐지한 셸 프로파일을 한 번만 탐지해 기억한다. 탐지는 외부 명령을 띄우므로 수 초가 걸릴 수 있다.
 *
 * <p>StateActor 가 없는 M2 의 메모이제이션이다(ADR-0009). 공유 상태는 이 불변 목록 하나뿐이다.
 */
public final class ProfileCatalog implements ListProfilesUseCase {
    private final Once<List<ShellProfile>> detection;

    public ProfileCatalog(PlatformModule platform) {
        Objects.requireNonNull(platform, "platform");
        this.detection = new Once<>(() -> List.copyOf(platform.shells()), "셸 탐지가 예외로 끝났다(PlatformModule.shells 계약 위반)");
    }

    @Override
    public List<ShellProfile> list() {
        return detection.get();
    }
}
