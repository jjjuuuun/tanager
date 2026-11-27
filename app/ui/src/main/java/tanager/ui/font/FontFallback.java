package tanager.ui.font;

import java.util.Objects;
import tanager.ui.i18n.Msg;

/**
 * 설정한 터미널 글꼴을 쓸 수 없어 번들 글꼴을 쓴다(FR-UI-06, 리스크 R2). 상태줄 경고 한 줄이다.
 *
 * @param requested 설정의 글꼴 이름
 * @param used 대신 쓰는 글꼴 패밀리
 * @param reason 쓸 수 없는 까닭
 */
public record FontFallback(String requested, String used, Reason reason) {
    /** 쓸 수 없는 까닭. */
    public enum Reason {
        /** 이 PC 에 설치되지 않았다. */
        NOT_FOUND,
        /** 한글 글리프가 없다 — JediTerm 은 글자 단위 대체 글꼴이 없어 한글이 네모로 보인다. */
        NO_HANGUL
    }

    public FontFallback {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(used, "used");
        Objects.requireNonNull(reason, "reason");
    }

    /** 상태줄에 보일 문장. */
    public String message() {
        return switch (reason) {
            case NOT_FOUND -> Msg.TERMINAL_FONT_MISSING.text(requested, used);
            case NO_HANGUL -> Msg.TERMINAL_FONT_NO_HANGUL.text(requested, used);
        };
    }
}
