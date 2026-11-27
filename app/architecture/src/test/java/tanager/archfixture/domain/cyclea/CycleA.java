package tanager.archfixture.domain.cyclea;

import tanager.archfixture.domain.cycleb.CycleB;

/** 위반 픽스처: domain 컨텍스트 패키지가 서로를 참조해 순환을 만든다(A → B → A). */
public class CycleA {
    CycleB other;
}
