package dev.tanager.archfixture.domain.onewaya;

import dev.tanager.archfixture.domain.onewayb.OneWayB;

/** 허용 픽스처: 컨텍스트 의존이 한 방향(A → B)이면 순환이 아니다(session → history 와 같은 모양). */
public class OneWayA {
    OneWayB other;
}
