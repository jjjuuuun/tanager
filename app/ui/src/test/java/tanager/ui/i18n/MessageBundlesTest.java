package tanager.ui.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 두 번들이 같은 키 · 같은 인자를 갖는지(#136). 빠진 번역은 실행 중 MissingResourceException 이 된다. */
class MessageBundlesTest {
    private static final String DIR = "/tanager/ui/i18n/";

    private static Properties load(String file) throws IOException {
        try (InputStream in = MessageBundlesTest.class.getResourceAsStream(DIR + file)) {
            assertThat(in).as(file).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }

    private static Set<String> keys() {
        return Arrays.stream(Msg.values()).map(Msg::key).collect(Collectors.toCollection(TreeSet::new));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"messages.properties", "messages_ko.properties"})
    @DisplayName("번들의 키는 Msg 와 정확히 같다(빠진 것도 남는 것도 없다)")
    void bundleKeysMatchMsg(String file) throws IOException {
        assertThat(new TreeSet<>(load(file).stringPropertyNames())).isEqualTo(keys());
    }

    @Test
    @DisplayName("같은 키는 두 언어에서 인자 개수가 같고, 모든 문장이 MessageFormat 으로 읽힌다")
    void argumentCountsMatch() throws IOException {
        Properties en = load("messages.properties");
        Properties ko = load("messages_ko.properties");
        for (String key : keys()) {
            int enArgs = new MessageFormat(en.getProperty(key)).getFormatsByArgumentIndex().length;
            int koArgs = new MessageFormat(ko.getProperty(key)).getFormatsByArgumentIndex().length;
            assertThat(koArgs).as(key).isEqualTo(enArgs);
        }
    }

    @Test
    @DisplayName("형식을 적용한 뒤 중괄호 · 홀로 남은 작은따옴표 문제가 없다")
    void formattedTextHasNoLeftovers() throws IOException {
        for (String file : new String[] {"messages.properties", "messages_ko.properties"}) {
            Properties bundle = load(file);
            for (String key : keys()) {
                MessageFormat format = new MessageFormat(bundle.getProperty(key));
                Object[] args = new Object[format.getFormatsByArgumentIndex().length];
                Arrays.setAll(args, i -> "ARG" + i);
                String text = format.format(args);
                assertThat(text).as(file + " " + key).doesNotContain("{").doesNotContain("}");
                for (Object arg : args) {
                    assertThat(text).as(file + " " + key).contains((String) arg);
                }
            }
        }
    }
}
