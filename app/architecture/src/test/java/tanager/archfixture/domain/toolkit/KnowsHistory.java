package tanager.archfixture.domain.toolkit;

import tanager.archfixture.domain.history.Place;
import tanager.archfixture.domain.shared.Token;

/** 허용 픽스처: 도구함 컨텍스트는 history · shared 만 안다. */
public record KnowsHistory(Place place, Token token) {}
