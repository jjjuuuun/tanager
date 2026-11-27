package tanager.ui.i18n;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * 화면 언어 하나의 문장 모음(#136, ADR-0017). 한국어 표시 언어면 한국어, 그 밖은 영어(기본 번들)다. 언어는 앱을 시작할 때 한 번 정한다 — 바꾸려면
 * 다시 시작한다.
 */
public final class Messages {
    private static final String BUNDLE = "tanager.ui.i18n.messages";

    /** JVM 의 표시 언어로 고른 모음. */
    static final Messages DEFAULT = of(Locale.getDefault(Locale.Category.DISPLAY));

    private final ResourceBundle bundle;

    private Messages(ResourceBundle bundle) {
        this.bundle = bundle;
    }

    /** {@code locale} 에 맞는 모음. 영어를 골라도 JVM 기본 언어 번들로 넘어가지 않게 대체 규칙을 끈다. */
    public static Messages of(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        Locale chosen = "ko".equals(locale.getLanguage()) ? Locale.KOREAN : Locale.ROOT;
        return new Messages(ResourceBundle.getBundle(
                BUNDLE, chosen, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)));
    }

    /** {@code msg} 의 문장. 인자는 문자열로 바꿔 넣는다 — 숫자에 자리 구분 쉼표(1,234)가 붙지 않게. */
    public String text(Msg msg, Object... args) {
        Objects.requireNonNull(msg, "msg");
        Object[] strings = Arrays.stream(args).map(String::valueOf).toArray();
        return new MessageFormat(bundle.getString(msg.key()), Locale.ROOT).format(strings);
    }
}
