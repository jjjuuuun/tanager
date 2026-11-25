package dev.tanager.platform.windows;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** {@code wsl.exe --list --quiet} 출력에서 배포판 이름을 뽑는다. 기본 출력은 UTF-16LE, {@code WSL_UTF8=1} 이면 UTF-8. */
final class WslListParser {

    private WslListParser() {}

    /** 출력 순서대로, 중복은 처음 것만 남긴 불변 목록. 대소문자는 그대로 둔다. */
    static List<String> parse(byte[] output) {
        Set<String> names = new LinkedHashSet<>();
        for (String line : decode(output).split("\\r?\\n")) {
            String name = line.strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static String decode(byte[] b) {
        String text;
        if (startsWith(b, 0xFF, 0xFE)) {
            text = new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        } else if (startsWith(b, 0xEF, 0xBB, 0xBF)) {
            text = new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        } else {
            text = new String(b, charsetWithoutBom(b));
        }
        return text.replace("\uFEFF", "").replace("\u0000", "");
    }

    private static Charset charsetWithoutBom(byte[] b) {
        for (byte x : b) {
            if (x == 0) {
                return StandardCharsets.UTF_16LE;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean startsWith(byte[] b, int... prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        return Arrays.equals(Arrays.copyOf(b, prefix.length), toBytes(prefix));
    }

    private static byte[] toBytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
