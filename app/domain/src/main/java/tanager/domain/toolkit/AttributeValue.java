package tanager.domain.toolkit;

import java.util.Objects;

/**
 * 항목 속성의 값. {@link Masked} 는 값을 갖지 않는다 — 비밀값이 도메인 객체(와 그 {@code toString})에 남을 길이 없다(FR-TK-07,
 * NFR-06).
 */
public sealed interface AttributeValue {

    /** 일반 값. 불변식: 제어 문자 없음, 500 코드 포인트 이하. */
    record Plain(String text) implements AttributeValue {
        /** 일반 값의 최대 길이(코드 포인트). */
        public static final int MAX_CODE_POINTS = 500;

        public Plain {
            Objects.requireNonNull(text, "text");
            if (Texts.hasControl(text)) {
                throw new IllegalArgumentException("속성 값에 제어 문자가 있다");
            }
            if (Texts.codePoints(text) > MAX_CODE_POINTS) {
                throw new IllegalArgumentException("속성 값이 " + MAX_CODE_POINTS + " 코드 포인트를 넘는다");
            }
        }
    }

    /** 마스킹된 값. 원래 값은 공급자가 읽는 즉시 버린다. */
    record Masked() implements AttributeValue {}
}
