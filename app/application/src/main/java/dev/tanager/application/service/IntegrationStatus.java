package dev.tanager.application.service;

import dev.tanager.application.port.out.Integration;
import dev.tanager.application.port.out.PlatformModule;
import java.util.Objects;

/**
 * 셸 통합 설치를 프로세스에서 한 번만 하고 그 결과를 모두가 같이 쓴다(ADR-0014 "한 번 · 기다림"). 설치는 파일 I/O 라 EDT 에서
 * 기다리지 않는다 — 조립 루트가 시작할 때 백그라운드에서 데우고, 셸을 띄우는 쪽(백그라운드 스레드)이 그 결과를 기다린다.
 *
 * <p>포트 계약은 던지지 않는 것이지만, 어기면 예외를 {@link Integration.Unavailable} 로 바꿔 기억한다 — 다시 설치하지 않는다.
 */
public final class IntegrationStatus {
    private static final System.Logger LOG = System.getLogger(IntegrationStatus.class.getName());

    private final Once<Integration> installation;

    public IntegrationStatus(PlatformModule platform) {
        Objects.requireNonNull(platform, "platform");
        this.installation = new Once<>(() -> install(platform), "셸 통합 설치가 오류로 끝났다");
    }

    /** 설치 결과. 처음 부르면 설치하고, 설치 중이면 끝날 때까지 기다린다. */
    public Integration await() {
        return installation.get();
    }

    private static Integration install(PlatformModule platform) {
        try {
            return Objects.requireNonNull(platform.integration().install(), "install()");
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "셸 통합 설치가 예외로 끝났다", e);
            return new Integration.Unavailable(
                    "셸 통합 설치가 예외로 끝났다: " + e.getClass().getSimpleName());
        }
    }
}
