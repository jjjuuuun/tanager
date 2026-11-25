package dev.tanager.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OS 분기는 bootstrap 의 플랫폼 선택 한 곳에만 둔다(architecture.md). ArchUnit 은 {@code System.getProperty} 의
 * 인자 값을 못 보므로, 제품 소스의 문자열 리터럴 {@code "os.name"} 을 찾는다.
 */
class OsNameSourceTest {
    // Gradle 은 테스트를 모듈 폴더(app/architecture)에서 돌린다
    private static final Path APP = Path.of("..");
    private static final Path BOOTSTRAP = APP.resolve("bootstrap");

    @Test
    @DisplayName("bootstrap 밖의 제품 소스는 os.name 을 읽지 않는다")
    void onlyBootstrapReadsOsName() throws IOException {
        // given
        List<Path> files;
        try (Stream<Path> walk = Files.walk(APP)) {
            files = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .toList();
        }
        // when
        List<String> outside = new ArrayList<>();
        int inBootstrap = 0;
        for (Path file : files) {
            int hits = OsNameReads.count(Files.readString(file, StandardCharsets.UTF_8));
            if (file.normalize().startsWith(BOOTSTRAP.normalize())) {
                inBootstrap += hits;
            } else if (hits > 0) {
                outside.add(APP.relativize(file).toString());
            }
        }
        // then
        assertThat(files).as("검사한 제품 소스(공허하게 통과하지 않게)").hasSizeGreaterThan(10);
        assertThat(inBootstrap)
                .as("bootstrap 은 플랫폼 선택에서 os.name 을 읽는다(검사가 실제로 찾는지)")
                .isPositive();
        assertThat(outside).isEmpty();
    }

    @Test
    @DisplayName("문자열 리터럴 \"os.name\" 만 센다(주석 · 다른 문자열 · 접근자 os.name() 은 아님)")
    void countsOnlyStringLiterals() {
        assertThat(OsNameReads.count("String n = System.getProperty(\"os.name\");"))
                .isEqualTo(1);
        assertThat(OsNameReads.count("var k = \"os.name\"; var j = \"os.name\";"))
                .isEqualTo(2);
        assertThat(OsNameReads.count("// Java 의 os.name 은 \"Mac OS X\"")).isZero();
        assertThat(OsNameReads.count("return os.name().startsWith(\"Windows\");"))
                .isZero();
        assertThat(OsNameReads.count("/* \"os.name\" 을 읽지 않는다 */")).isZero();
        assertThat(OsNameReads.count("String s = \"os.name.extra\";")).isZero();
    }
}
