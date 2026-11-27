package tanager.archfixture.ui;

import tanager.archfixture.application.port.in.EventsFromState;

/** 허용 픽스처: ui 가 application 의 포트만 쓴다. */
public class UsesPort {
    EventsFromState events;
}
