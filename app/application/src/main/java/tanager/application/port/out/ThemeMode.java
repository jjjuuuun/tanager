package tanager.application.port.out;

/** 화면 테마(config {@code appearance.theme}, SDD §9.3 · FR-UI-01). */
public enum ThemeMode {
    /** OS 의 다크/라이트 설정을 따른다. */
    SYSTEM,
    /** 늘 다크. */
    DARK,
    /** 늘 라이트. */
    LIGHT
}
