package dev.tanager.archfixture.application.platform;

import dev.tanager.archfixture.application.port.out.NeedsOs;

/** 허용 픽스처: 이미 있는 platform ↔ port.out 순환(PlatformModule · OperatingSystem)을 흉내 낸다 — 규칙이 이 간선만 뺀다. */
public class FixtureOs {
    NeedsOs module;
}
