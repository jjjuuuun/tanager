package dev.tanager.ui.font;

import dev.tanager.domain.shared.Result;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/** 번들 터미널 글꼴(D2Coding, OFL-1.1). JediTerm 은 대체 글꼴이 없어 기본 글꼴에 한글이 있어야 한다(ADR-0010). */
public final class TerminalFonts {
    public static final String FAMILY = "D2Coding";
    /** 음절 · 호환 자모(자음 · 모음)를 섞은 표본. IME 조합 중 글자는 자모로 그려진다. */
    public static final String HANGUL_SAMPLE = "한글가힣ㄱㅎㅏㅣ";

    private static final System.Logger LOG = System.getLogger(TerminalFonts.class.getName());
    private static final List<String> RESOURCES =
            List.of("/fonts/D2Coding-Ver1.4.0-20261003.ttf", "/fonts/D2CodingBold-Ver1.4.0-20261003.ttf");

    private TerminalFonts() {}

    /** Regular · Bold 를 이 JVM 에 등록하고 Regular(크기 1 — 쓰는 쪽이 deriveFont)를 돌려준다. */
    public static Result<Font, String> registerBundled() {
        List<Font> fonts = new ArrayList<>();
        for (String resource : RESOURCES) {
            switch (load(resource)) {
                case Result.Ok<Font, String> ok -> fonts.add(ok.get());
                case Result.Err<Font, String> err -> {
                    return Result.err(err.get());
                }
            }
        }
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (Font font : fonts) {
            if (!env.registerFont(font)) {
                // 같은 이름이 이미 있다(시스템 설치본 또는 두 번째 호출). 이름으로 찾는 데는 문제없다
                LOG.log(System.Logger.Level.DEBUG, "이미 등록된 글꼴: {0}", font.getFontName());
            }
        }
        return Result.ok(fonts.getFirst());
    }

    private static Result<Font, String> load(String resource) {
        try (InputStream in = TerminalFonts.class.getResourceAsStream(resource)) {
            if (in == null) {
                return Result.err("글꼴 리소스 없음: " + resource);
            }
            return Result.ok(Font.createFont(Font.TRUETYPE_FONT, in));
        } catch (IOException | FontFormatException e) {
            return Result.err("글꼴을 읽을 수 없음: " + resource + " — " + e.getMessage());
        }
    }

    /** 이 글꼴이 {@link #HANGUL_SAMPLE} 을 모두 그릴 수 있는가. */
    public static boolean supportsHangul(Font font) {
        return font.canDisplayUpTo(HANGUL_SAMPLE) == -1;
    }

    /** 코드 포인트마다 그릴 수 있는지 판정하는 함수로 본 한글 지원 여부(테스트용 순수 판정). */
    static boolean supportsHangul(IntPredicate canDisplay) {
        return HANGUL_SAMPLE.codePoints().allMatch(canDisplay);
    }
}
