package dev.tanager.archfixture.domain;

import java.nio.charset.StandardCharsets;

/** 허용 픽스처: domain 이 java.nio.charset.StandardCharsets 만 쓴다(ADR-0015, D14). */
public class StandardCharsetsInDomain {
    byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
