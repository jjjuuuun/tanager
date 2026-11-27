package tanager.archfixture.domain;

/** 위반 픽스처: 같은 java.nio.charset 패키지라도 StandardCharsets 가 아닌 클래스는 금지다(ADR-0015). */
public class CharsetInDomain {
    String name() {
        return java.nio.charset.Charset.defaultCharset().name();
    }
}
