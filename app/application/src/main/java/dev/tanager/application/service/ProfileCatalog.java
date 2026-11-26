package dev.tanager.application.service;

import dev.tanager.application.port.in.ListProfilesUseCase;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.domain.session.ShellProfile;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * 플랫폼이 탐지한 셸 프로파일을 한 번만 탐지해 기억한다. 탐지는 외부 명령을 띄우므로 수 초가 걸릴 수 있다.
 *
 * <p>StateActor 가 없는 M2 의 메모이제이션이다(ADR-0009). 공유 상태는 이 불변 목록 하나뿐이다.
 */
public final class ProfileCatalog implements ListProfilesUseCase {
    // FutureTask.run 은 처음 한 번만 실행되고, 동시에 부른 다른 스레드는 get 에서 그 결과를 기다린다.
    private final FutureTask<List<ShellProfile>> detection;

    public ProfileCatalog(PlatformModule platform) {
        Objects.requireNonNull(platform, "platform");
        this.detection = new FutureTask<>(() -> List.copyOf(platform.shells()));
    }

    @Override
    public List<ShellProfile> list() {
        detection.run();
        return awaitDetection();
    }

    // 다른 스레드가 탐지 중일 때 인터럽트를 받아도 결과를 기다리고, 끝난 뒤 인터럽트 상태를 되살린다.
    private List<ShellProfile> awaitDetection() {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return detection.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    throw new IllegalStateException("셸 탐지가 예외로 끝났다(PlatformModule.shells 계약 위반)", e.getCause());
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
