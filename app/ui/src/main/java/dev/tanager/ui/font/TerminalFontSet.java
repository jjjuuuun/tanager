package dev.tanager.ui.font;

import java.awt.Font;
import java.util.Objects;

/**
 * 터미널이 그릴 때 쓰는 글꼴 한 벌. 번들 D2Coding 의 Regular · Bold 객체를 이름으로 다시 찾지 않고 그대로 쓴다 — 시스템에 다른
 * 버전의 D2Coding 이 설치돼 있어도 섞이지 않게(ADR-0011).
 *
 * @param regular 보통 글자
 * @param bold 굵은 글자(번들 Bold 면 합성 굵게가 아니다)
 */
public record TerminalFontSet(Font regular, Font bold) {
    public TerminalFontSet {
        Objects.requireNonNull(regular, "regular");
        Objects.requireNonNull(bold, "bold");
    }

    /** 두 글꼴을 같은 크기({@code points})로 만든 새 한 벌. */
    public TerminalFontSet sized(float points) {
        return new TerminalFontSet(regular.deriveFont(points), bold.deriveFont(points));
    }

    /** 번들 글꼴을 쓸 수 없을 때의 논리 글꼴 {@code Monospaced}(JDK 가 한글 대체를 붙여 조합 중 글자도 그린다). 크기 1. */
    public static TerminalFontSet fallback() {
        return new TerminalFontSet(new Font(Font.MONOSPACED, Font.PLAIN, 1), new Font(Font.MONOSPACED, Font.BOLD, 1));
    }
}
