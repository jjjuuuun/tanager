package tanager.archfixture.application.port.in;

import tanager.archfixture.application.state.StateEvent;

/** 위반 픽스처: 포트가 state 의 타입을 쓰고, state 도 포트를 써서 순환이 된다(port.in → state → port.in). */
public interface EventsFromState {
    void on(StateEvent event);
}
