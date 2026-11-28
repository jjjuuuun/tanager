package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 설치(MSI · zip)가 밀어 놓은 jar 수정 시각을 AOT 캐시를 만들 때 값으로 되돌린다(#221). JVM 은 jar 의 수정 시각이 다르면 캐시를
 * 버린다 — MSI · zip 은 시각을 현지 시간 2초 단위로 담아 다른 시간대 · 홀수 초에서 늘 달라진다.
 */
class ClasspathStampsTest {
    private static final Instant TRAINED = Instant.parse("2026-11-28T06:39:07.6868871Z");
    private static final Instant INSTALLED = Instant.parse("2026-11-27T21:39:06Z");

    @TempDir
    Path app;

    @Test
    @DisplayName("내용이 같은 jar 의 밀린 수정 시각을 되돌린다")
    void restoresDriftedTime() throws IOException {
        // given
        Path jar = jar("tanager-ui.jar", "ui classes", INSTALLED);
        Path manifest = manifest(stampOf("tanager-ui.jar", "ui classes", TRAINED));

        // when
        int restored = ClasspathStamps.restore(manifest);

        // then
        assertThat(restored).isEqualTo(1);
        assertThat(Files.getLastModifiedTime(jar).toInstant()).isEqualTo(TRAINED);
    }

    @Test
    @DisplayName("크기는 같아도 내용이 다르면 그대로 둔다 — 캐시가 다른 클래스를 내놓으면 안 된다")
    void keepsChangedContent() throws IOException {
        // given
        Path jar = jar("tanager-ui.jar", "ui CLASSES", INSTALLED);
        Path manifest = manifest(stampOf("tanager-ui.jar", "ui classes", TRAINED));

        // when
        int restored = ClasspathStamps.restore(manifest);

        // then
        assertThat(restored).isZero();
        assertThat(Files.getLastModifiedTime(jar).toInstant()).isEqualTo(INSTALLED);
    }

    @Test
    @DisplayName("맞는 시각 · 없는 jar · 형식이 틀린 줄은 건너뛰고 나머지를 본다")
    void skipsUnchangedMissingAndMalformed() throws IOException {
        // given
        jar("same.jar", "same", TRAINED);
        Path drifted = jar("drifted.jar", "drifted", INSTALLED);
        Path manifest = manifest(
                stampOf("same.jar", "same", TRAINED),
                stampOf("gone.jar", "gone", TRAINED),
                "깨진 줄",
                stampOf("drifted.jar", "drifted", TRAINED));

        // when
        int restored = ClasspathStamps.restore(manifest);

        // then
        assertThat(restored).isEqualTo(1);
        assertThat(Files.getLastModifiedTime(drifted).toInstant()).isEqualTo(TRAINED);
    }

    @Test
    @DisplayName("기록 파일이 없으면 아무것도 하지 않는다")
    void missingManifest() {
        // when · then
        assertThat(ClasspathStamps.restore(app.resolve("tanager.aot.stamps"))).isZero();
    }

    private Path jar(String name, String content, Instant modified) throws IOException {
        Path jar = Files.writeString(app.resolve(name), content);
        Files.setLastModifiedTime(jar, FileTime.from(modified));
        return jar;
    }

    private Path manifest(String... lines) throws IOException {
        return Files.write(app.resolve("tanager.aot.stamps"), java.util.List.of(lines), StandardCharsets.UTF_8);
    }

    private static String stampOf(String name, String content, Instant modified) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return sha256(bytes) + " " + bytes.length + " " + modified + " " + name;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
