package dev.tanager.archfixture.application.service;

import dev.tanager.archfixture.application.port.in.EventsFromState;

/** 허용 픽스처: service → port.in 한 방향 의존은 순환이 아니다. */
public class OneWayService {
    EventsFromState events;
}
