package dev.tanager.archfixture.ui;

import dev.tanager.archfixture.application.state.StateEvent;

/** 위반 픽스처: ui 가 application.state 를 직접 쓴다(포트만 써야 함). */
public class TouchesState {
    StateEvent event;
}
