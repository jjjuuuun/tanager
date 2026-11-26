package dev.tanager.bootstrap;

import dev.tanager.adapter.persistence.JsonConfigRepository;
import dev.tanager.application.port.in.ListProfilesUseCase;
import dev.tanager.application.port.in.StartSessionUseCase;
import dev.tanager.application.port.out.Integration;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.application.port.out.PtyLauncher;
import dev.tanager.application.service.DefaultProfileMemory;
import dev.tanager.application.service.ProfileCatalog;
import dev.tanager.application.service.SessionStarter;
import java.util.Objects;

/** 조립한 유스케이스들. 포트 구현을 골라 서비스에 넣는 일은 여기서만 한다(ADR-0002). */
record Assembly(ListProfilesUseCase profiles, StartSessionUseCase sessions) {
    private static final System.Logger LOG = System.getLogger(Assembly.class.getName());
    private static final String CONFIG_FILE = "config.json";

    Assembly {
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(sessions, "sessions");
    }

    /**
     * 셸 탐지는 수 초 걸릴 수 있어 창을 띄우는 동안 가상 스레드에서 미리 돌린다 — 첫 {@code start} 는 그 결과를 기다린다(탐지는
     * 한 번뿐이다).
     */
    static Assembly assemble(PlatformModule platform, PtyLauncher launcher) {
        ProfileCatalog catalog = new ProfileCatalog(platform);
        Thread.ofVirtual().name("tanager-shell-detect").start(() -> {
            try {
                catalog.list();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "셸 탐지 실패", e);
            }
        });
        installShellIntegration(platform);
        JsonConfigRepository config =
                new JsonConfigRepository(platform.dirs().appData().resolve(CONFIG_FILE));
        return new Assembly(
                catalog, new SessionStarter(catalog, config, platform, launcher, new DefaultProfileMemory(config)));
    }

    /** 파일 I/O 라 백그라운드에서 한 번 돌린다. 결과는 아직 로그에만 남긴다 — 셸에 주입하는 일은 #55 · #81. */
    private static void installShellIntegration(PlatformModule platform) {
        Thread.ofVirtual().name("tanager-shell-integration").start(() -> {
            try {
                switch (platform.integration().install()) {
                    case Integration.Installed(var directory) ->
                        LOG.log(System.Logger.Level.INFO, "셸 통합 스크립트: {0}", directory);
                    case Integration.Unavailable(var reason) ->
                        LOG.log(System.Logger.Level.WARNING, "셸 통합 없음: {0}", reason);
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "셸 통합 설치 실패", e);
            }
        });
    }
}
