package dev.tanager.application.port.in;

import dev.tanager.application.port.out.ConfigError;
import dev.tanager.application.port.out.ConfigRepository;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.shared.Result;

/** 기본 셸(config.json 의 defaultProfile)을 기억한다(FR-SES-02). 파일 I/O 로 블로킹되므로 EDT 에서 부르지 않는다. */
public interface RememberDefaultProfileUseCase {

    /**
     * {@code profile} 을 기본 프로파일로 저장한다. 설정을 읽지 못하면 저장하지 않고 그 오류를 돌려준다. 이미 같은 값이면 파일을 건드리지
     * 않는다. 모르는 키 보존과 손상 · 상위 버전 파일 거부는 {@link ConfigRepository#save} 가 맡는다.
     */
    Result<Remembered, ConfigError> remember(ProfileId profile);
}
