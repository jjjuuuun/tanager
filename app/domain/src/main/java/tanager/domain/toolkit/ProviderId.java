package tanager.domain.toolkit;

import java.util.Objects;
import java.util.regex.Pattern;

/** 도구함 공급자 식별자(예 {@code claude-code}). 불변식: {@code [a-z0-9][a-z0-9-]{0,31}}. */
public record ProviderId(String value) {
    private static final Pattern FORM = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    public ProviderId {
        Objects.requireNonNull(value, "value");
        if (!FORM.matcher(value).matches()) {
            throw new IllegalArgumentException("공급자 식별자 형식이 아니다: " + value);
        }
    }
}
