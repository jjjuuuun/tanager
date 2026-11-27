package tanager.archfixture.domain.cycleb;

import tanager.archfixture.domain.cyclea.CycleA;

/** 위반 픽스처: CycleA 를 되참조한다. */
public class CycleB {
    CycleA other;
}
