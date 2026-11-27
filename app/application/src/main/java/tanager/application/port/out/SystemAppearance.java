package tanager.application.port.out;

/**
 * OS 의 외관 설정(SDD §6.7). 지금은 '애니메이션 효과'만 담는다 — 다크/라이트는 FR-UI-01(M5)에서 더한다.
 *
 * @param animations OS 가 애니메이션을 허용하는가. 끄면 UI 전환은 즉시 최종 값이 된다(FR-UI-04)
 */
public record SystemAppearance(boolean animations) {

    /** OS 값을 읽지 못했을 때 쓰는 기본값 — 애니메이션을 켠다. */
    public static final SystemAppearance DEFAULT = new SystemAppearance(true);
}
