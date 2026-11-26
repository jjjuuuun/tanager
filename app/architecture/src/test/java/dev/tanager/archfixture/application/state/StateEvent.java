package dev.tanager.archfixture.application.state;

import dev.tanager.archfixture.application.port.in.EventsFromState;

/** 위반 픽스처: EventsFromState 를 되참조한다. */
public class StateEvent {
    EventsFromState events;
}
