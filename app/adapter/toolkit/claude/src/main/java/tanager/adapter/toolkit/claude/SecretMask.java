package tanager.adapter.toolkit.claude;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tanager.domain.toolkit.Attribute;
import tanager.domain.toolkit.AttributeValue;

/**
 * 비밀일 수 있는 값을 읽는 자리에서 버린다(FR-TK-07 · NFR-06, SDD §7.2). env · headers 는 값을 아예 읽지 않고 키만 {@link
 * AttributeValue.Masked} 로, 명령 · 인수는 토큰류만 {@link #MASK} 로 바꾼 글자로, URL 은 스킴 · 호스트 · 포트만 남긴다. 순수 함수다 — 로그를
 * 남기지 않고 예외 메시지(원문 조각이 든다)를 쓰지 않는다.
 */
final class SecretMask {

    /** 가린 자리에 보이는 글자. */
    static final String MASK = "••••";

    /** 이 길이 이상이고 글자 · 숫자가 섞인 고엔트로피 덩어리는 토큰으로 본다. */
    private static final int TOKEN_LENGTH = 32;

    /** 글자당 비트. 임의 base62 토큰은 4.5 안팎, 영어 낱말을 이은 이름은 이보다 낮다. */
    private static final double TOKEN_ENTROPY = 3.5;

    /** 이름에 이 글자가 들면 그 값은 비밀로 본다(플래그 · NAME=값 · 쿼리 이름, 대소문자 무시). */
    private static final List<String> SECRET_WORDS =
            List.of("key", "token", "secret", "passw", "pwd", "auth", "credential", "cookie", "signature");

    /** 값이 아니라 환경 변수 이름이므로 가리지 않는다. 기본값({@code ${A:-b}})이 붙으면 그 기본값이 비밀일 수 있어 가린다. */
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*}");

    private static final Pattern USERINFO = Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^/\\s?#@]+@");
    private static final Pattern QUERY = Pattern.compile("([?&;])([^=&#;?\\s]+)=([^&#;\\s]*)");
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(bearer|basic)\\s+\\S+");
    private static final Pattern ASSIGNMENT = Pattern.compile("([A-Za-z_][A-Za-z0-9_.-]*)=(.*)", Pattern.DOTALL);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_+=-]{" + TOKEN_LENGTH + ",}");

    private SecretMask() {}

    /** env · headers 의 키마다 {@code <prefix>.<키>} = Masked. 키는 한 줄로 편다(겹침은 {@link McpServer} 가 정리). */
    static List<Attribute> keysOnly(String prefix, List<String> keys) {
        List<Attribute> attributes = new ArrayList<>();
        for (String key : keys) {
            Frontmatter.clean(prefix + "." + key, AttributeValue.Plain.MAX_CODE_POINTS)
                    .ifPresent(name -> attributes.add(ClaudeItems.masked(name)));
        }
        return List.copyOf(attributes);
    }

    /** 명령과 인수를 공백으로 이은 한 줄. {@code command} 안의 공백도 인수 경계로 본다. */
    static String commandLine(String command, List<String> args) {
        List<String> words = new ArrayList<>();
        for (String word : command.strip().split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        words.addAll(args);
        List<String> out = new ArrayList<>(words.size());
        boolean hideNext = false;
        for (String word : words) {
            if (hideNext) {
                out.add(hideValue(word));
                hideNext = false;
            } else if (word.startsWith("-") && isSecretName(flagName(word))) {
                int equals = word.indexOf('=');
                if (equals < 0) {
                    out.add(word);
                    hideNext = true;
                } else {
                    out.add(word.substring(0, equals + 1) + hideValue(word.substring(equals + 1)));
                }
            } else if (word.equalsIgnoreCase("bearer") || word.equalsIgnoreCase("basic")) {
                out.add(word);
                hideNext = true;
            } else {
                out.add(redact(word));
            }
        }
        return String.join(" ", out);
    }

    /**
     * 글자 하나(인수 하나)에서 토큰류만 가린다 — URL 사용자 정보, 비밀 이름의 쿼리 값, {@code Bearer} · {@code Basic} 뒤, 비밀 이름의
     * {@code NAME=값}, 32자 이상 고엔트로피 덩어리.
     */
    static String redact(String text) {
        String out = USERINFO.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + MASK + "@"));
        out = QUERY.matcher(out)
                .replaceAll(m -> Matcher.quoteReplacement(
                        isSecretName(m.group(2)) ? m.group(1) + m.group(2) + "=" + MASK : m.group()));
        out = AUTH_SCHEME.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + " " + MASK));
        Matcher assignment = ASSIGNMENT.matcher(out);
        if (assignment.matches() && isSecretName(assignment.group(1))) {
            out = assignment.group(1) + "=" + hideValue(assignment.group(2));
        }
        return TOKEN.matcher(out).replaceAll(m -> Matcher.quoteReplacement(looksRandom(m.group()) ? MASK : m.group()));
    }

    /**
     * URL 의 스킴 · 호스트 · 포트(예 {@code https://api.example.com:8443}). 사용자 정보 · 경로 · 쿼리 · 조각은 버린다. URL 로 읽을 수
     * 없거나({@code ${VAR}} 를 쓴 URL 등) 호스트가 없으면 empty — 부르는 쪽이 값 전체를 가린다.
     */
    static Optional<String> origin(String url) {
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty()) {
            return Optional.empty();
        }
        String origin = scheme.toLowerCase(Locale.ROOT) + "://" + host.toLowerCase(Locale.ROOT);
        return Optional.of(uri.getPort() >= 0 ? origin + ":" + uri.getPort() : origin);
    }

    private static String hideValue(String value) {
        return REFERENCE.matcher(value).matches() ? value : MASK;
    }

    private static String flagName(String word) {
        int equals = word.indexOf('=');
        return equals < 0 ? word : word.substring(0, equals);
    }

    private static boolean isSecretName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String word : SECRET_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksRandom(String chunk) {
        boolean letter = false;
        boolean digit = false;
        Map<Character, Integer> counts = new HashMap<>();
        for (char c : chunk.toCharArray()) {
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
            counts.merge(c, 1, Integer::sum);
        }
        if (!letter || !digit) {
            return false;
        }
        double entropy = 0;
        for (int count : counts.values()) {
            double p = (double) count / chunk.length();
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy >= TOKEN_ENTROPY;
    }
}
