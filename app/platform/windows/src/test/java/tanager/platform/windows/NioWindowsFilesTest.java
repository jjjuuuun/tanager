package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 실제 파일시스템으로 답하는 {@link NioWindowsFiles}. 경로 문자열은 실행하는 OS 의 것이다. */
class NioWindowsFilesTest {
    private final NioWindowsFiles files = new NioWindowsFiles();

    @Test
    @DisplayName("디렉터리는 true, 파일은 false, 없는 경로는 false 다")
    void answersFromFileSystem(@TempDir Path dir) throws IOException {
        // given
        Path file = Files.writeString(dir.resolve("a.txt"), "x");

        // when / then
        assertThat(files.isDirectory(dir.toString())).contains(true);
        assertThat(files.isDirectory(file.toString())).contains(false);
        assertThat(files.isDirectory(dir.resolve("gone").toString())).contains(false);
    }

    @Test
    @DisplayName("경로로 쓸 수 없는 문자열은 모름(empty)이다")
    void invalidPathIsUnknown() {
        assertThat(files.isDirectory("a\u0000b")).isEmpty();
        assertThat(files.readText("a\u0000b")).isEmpty();
    }

    @Test
    @DisplayName("readText 는 UTF-8 로 읽고, 없는 파일은 빈 문자열이다")
    void readsTextOrEmptyWhenMissing(@TempDir Path dir) throws IOException {
        // given
        Path conf = Files.writeString(dir.resolve("wsl.conf"), "[automount]\nroot = /윈/\n", StandardCharsets.UTF_8);

        // when / then
        assertThat(files.readText(conf.toString())).contains("[automount]\nroot = /윈/\n");
        assertThat(files.readText(dir.resolve("gone").toString())).contains("");
    }

    @Test
    @DisplayName("디렉터리를 파일로 읽으려 하면 모름(empty)이다")
    void readingDirectoryIsUnknown(@TempDir Path dir) {
        assertThat(files.readText(dir.toString())).isEmpty();
    }
}
