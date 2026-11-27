package tanager.ui.theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** 번들된 소프트 테마 두 개. 색은 각 리소스 파일에만 있다. */
public enum SoftTheme {
    DARK("themes/soft-dark.properties", true),
    LIGHT("themes/soft-light.properties", false);

    private final String resource;
    private final boolean dark;

    SoftTheme(String resource, boolean dark) {
        this.resource = resource;
        this.dark = dark;
    }

    /** 클래스패스 리소스 경로. */
    public String resource() {
        return resource;
    }

    public boolean dark() {
        return dark;
    }

    /** 테마 파일의 색 변수(대비 검사용). */
    ThemeFile load() {
        try (InputStream in = open()) {
            return ThemeFile.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(resource, e);
        }
    }

    /** FlatLaf 에 넘길 전체 속성. 파일은 UTF-8(주석에 한글). */
    Properties properties() {
        try (Reader reader = new InputStreamReader(open(), StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException(resource, e);
        }
    }

    private InputStream open() throws IOException {
        InputStream in = SoftTheme.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("테마 리소스 없음: " + resource);
        }
        return in;
    }
}
