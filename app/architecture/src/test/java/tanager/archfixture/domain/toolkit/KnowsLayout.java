package tanager.archfixture.domain.toolkit;

import tanager.archfixture.domain.layout.Pane;

/** 위반 픽스처: 도구함 컨텍스트가 분할 레이아웃을 안다. */
public record KnowsLayout(Pane pane) {}
