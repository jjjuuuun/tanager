package tanager.ui.font;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntPredicate;
import tanager.domain.shared.Result;

/** 번들 터미널 글꼴(D2Coding, OFL-1.1). JediTerm 은 대체 글꼴이 없어 기본 글꼴에 한글이 있어야 한다(ADR-0010). */
public final class TerminalFonts {
    public static final String FAMILY = "D2Coding";
    /** 음절 · 호환 자모(자음 · 모음)를 섞은 표본. IME 조합 중 글자는 자모로 그려진다. */
    public static final String HANGUL_SAMPLE = "한글가힣ㄱㅎㅏㅣ";

    private static final System.Logger LOG = System.getLogger(TerminalFonts.class.getName());
    private static final List<String> RESOURCES =
            List.of("/fonts/D2Coding-Ver1.4.0-20261003.ttf", "/fonts/D2CodingBold-Ver1.4.0-20261003.ttf");

    private TerminalFonts() {}

    /**
     * Regular · Bold 를 이 JVM 에 등록하고 두 글꼴 객체(크기 1 — 쓰는 쪽이 {@link TerminalFontSet#sized})를 돌려준다. 실패는 모두
     * Err.
     */
    public static Result<TerminalFontSet, String> registerBundled() {
        return register(RESOURCES, TerminalFonts.class::getResourceAsStream);
    }

    /**
     * 글꼴 하위 시스템의 RuntimeException · Error 도 Err 로 바꾼다 — 글꼴이 없어도 창은 떠야 한다.
     * VirtualMachineError(메모리 부족 등)만은 삼키지 않는다. 단 InternalError 는 VirtualMachineError 의 하위지만 글꼴 하위
     * 시스템(FontManager)이 던지는 일이 있어 Err 로 바꾼다. 실패 주입 테스트가 {@code open} 을 바꾼다.
     */
    static Result<TerminalFontSet, String> register(List<String> resources, Function<String, InputStream> open) {
        try {
            return loadAndRegister(resources, open);
        } catch (InternalError e) {
            return Result.err("글꼴 등록 실패: " + e);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (RuntimeException | Error e) {
            return Result.err("글꼴 등록 실패: " + e);
        }
    }

    private static Result<TerminalFontSet, String> loadAndRegister(
            List<String> resources, Function<String, InputStream> open) {
        List<Font> fonts = new ArrayList<>();
        for (String resource : resources) {
            switch (load(resource, open)) {
                case Result.Ok<Font, String> ok -> fonts.add(ok.get());
                case Result.Err<Font, String> err -> {
                    return Result.err(err.get());
                }
            }
        }
        GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (Font font : fonts) {
            if (!env.registerFont(font)) {
                // 같은 이름의 글꼴이 이미 있다(시스템에 설치된 D2Coding 또는 두 번째 호출). 터미널은 돌려주는 Font 객체를
                // 직접 쓰므로 영향이 없다(ADR-0011). 등록은 이후 설정 화면에서 이름을 보여 주려고 한다
                LOG.log(System.Logger.Level.INFO, "같은 이름의 글꼴이 이미 있어 번들을 등록하지 않음: {0}", font.getFontName());
            }
        }
        // 첫째가 Regular, 둘째가 Bold. 하나만 주면(실패 주입 테스트) Bold 자리에도 같은 글꼴을 쓴다
        return Result.ok(new TerminalFontSet(fonts.getFirst(), fonts.getLast()));
    }

    private static Result<Font, String> load(String resource, Function<String, InputStream> open) {
        try (InputStream in = open.apply(resource)) {
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
        return supportsHangul(font::canDisplay);
    }

    /** 코드 포인트마다 그릴 수 있는지 판정하는 함수로 본 한글 지원 여부(테스트용 순수 판정). */
    static boolean supportsHangul(IntPredicate canDisplay) {
        return HANGUL_SAMPLE.codePoints().allMatch(canDisplay);
    }
}
