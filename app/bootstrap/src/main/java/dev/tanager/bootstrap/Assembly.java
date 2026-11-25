package dev.tanager.bootstrap;

import dev.tanager.adapter.persistence.JsonConfigRepository;
import dev.tanager.application.port.in.StartSessionUseCase;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.application.port.out.PtyLauncher;
import dev.tanager.application.service.ProfileCatalog;
import dev.tanager.application.service.SessionStarter;
import java.util.Objects;

/** 조립한 유스케이스들. 포트 구현을 골라 서비스에 넣는 일은 여기서만 한다(ADR-0002). */
record Assembly(StartSessionUseCase sessions) {
    private static final System.Logger LOG = System.getLogger(Assembly.class.getName());
    private static final String CONFIG_FILE = "config.json";

    Assembly {
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
        JsonConfigRepository config =
                new JsonConfigRepository(platform.dirs().appData().resolve(CONFIG_FILE));
        return new Assembly(new SessionStarter(catalog, config, platform, launcher));
    }
}
