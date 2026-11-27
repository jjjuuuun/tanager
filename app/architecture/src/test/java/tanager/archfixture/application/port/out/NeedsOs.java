package tanager.archfixture.application.port.out;

import tanager.archfixture.application.platform.FixtureOs;

/** 허용 픽스처: FixtureOs 를 되참조한다(port.out → platform 간선은 규칙에서 뺀다). */
public interface NeedsOs {
    FixtureOs os();
}
