package dev.tanager.platform.windows;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** {@code wsl.exe --list --quiet} 출력에서 배포판 이름을 뽑는다. 기본 출력은 UTF-16LE, {@code WSL_UTF8=1} 이면 UTF-8. */
final class WslListParser {
    private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    // WSL 이 허용하는 배포판 이름 문자. 안내 문구 · 잘못 디코딩된 바이트(U+FFFD 등)를 걸러 낸다.
    private static final Pattern DISTRO_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private WslListParser() {}

    /** 출력 순서대로, 중복은 처음 것만 남긴 불변 목록. 대소문자는 그대로 둔다. 배포판 이름 꼴이 아닌 줄은 버린다. */
    static List<String> parse(byte[] output) {
        Set<String> names = new LinkedHashSet<>();
        for (String line : decode(output).split("\\r?\\n")) {
            String name = line.strip();
            if (DISTRO_NAME.matcher(name).matches()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static String decode(byte[] b) {
        String text;
        if (startsWith(b, UTF16LE_BOM)) {
            text = new String(b, UTF16LE_BOM.length, b.length - UTF16LE_BOM.length, StandardCharsets.UTF_16LE);
        } else if (startsWith(b, UTF8_BOM)) {
            text = new String(b, UTF8_BOM.length, b.length - UTF8_BOM.length, StandardCharsets.UTF_8);
        } else {
            text = new String(b, guessCharset(b));
        }
        return text.replace("\uFEFF", "").replace("\u0000", "");
    }

    /** BOM 이 없을 때: 배포판 이름은 ASCII 라 UTF-16LE 면 NUL 바이트가 반드시 섞인다. */
    private static Charset guessCharset(byte[] b) {
        for (byte x : b) {
            if (x == 0) {
                return StandardCharsets.UTF_16LE;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean startsWith(byte[] b, byte[] prefix) {
        return b.length >= prefix.length && Arrays.equals(b, 0, prefix.length, prefix, 0, prefix.length);
    }
}
