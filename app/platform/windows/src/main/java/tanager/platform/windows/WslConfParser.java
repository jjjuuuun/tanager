package tanager.platform.windows;

import java.util.Optional;
import tanager.domain.history.WslMount;

/** 배포판의 {@code /etc/wsl.conf} 에서 {@code [automount] root} 를 읽는다(#53). 순수 함수. */
final class WslConfParser {
    private static final String SECTION = "automount";
    private static final String KEY = "root";

    private WslConfParser() {}

    /**
     * 마운트 루트. 없거나 절대 경로가 아니면 empty(호출 쪽이 기본 {@code /mnt/} 를 쓴다). 끝 {@code /} 가 없으면 붙이고, 같은 키가
     * 여러 번이면 마지막 값을 쓴다. 섹션 · 키 이름은 문서대로 소문자만 본다.
     */
    static Optional<WslMount> automountRoot(String conf) {
        Optional<String> root = Optional.empty();
        boolean inSection = false;
        for (String raw : conf.replace("﻿", "").split("\\r?\\n")) {
            String line = raw.strip();
            if (line.startsWith("[") && line.endsWith("]")) {
                inSection = line.substring(1, line.length() - 1).strip().equals(SECTION);
                continue;
            }
            int eq = line.indexOf('=');
            if (!inSection || line.startsWith("#") || line.startsWith(";") || eq < 0) {
                continue;
            }
            if (line.substring(0, eq).strip().equals(KEY)) {
                root = Optional.of(valueOf(line.substring(eq + 1)));
            }
        }
        return root.filter(value -> value.startsWith("/"))
                .map(WslConfParser::withTrailingSlash)
                .map(WslMount::new);
    }

    // 줄 끝 주석과 감싼 따옴표를 뗀다
    private static String valueOf(String raw) {
        int comment = raw.indexOf('#');
        String value = (comment < 0 ? raw : raw.substring(0, comment)).strip();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).strip();
        }
        return value;
    }

    private static String withTrailingSlash(String root) {
        return root.endsWith("/") ? root : root + "/";
    }
}
