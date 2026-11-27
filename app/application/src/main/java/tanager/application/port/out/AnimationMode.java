package tanager.application.port.out;

/** 전환 효과(config {@code appearance.animations}, SDD §9.3 · FR-UI-04). */
public enum AnimationMode {
    /** OS 의 애니메이션 효과 설정을 따른다. */
    AUTO,
    /** 늘 켠다. */
    ON,
    /** 늘 끈다 — 전환은 즉시 최종 값이 된다. */
    OFF
}
