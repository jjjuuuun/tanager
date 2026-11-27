package tanager.platform.windows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/** {@link java.nio.file.Files} 로 본다. */
final class NioWindowsFiles implements WindowsFiles {

    // Files.isDirectory 는 '없음'과 '접근 불가'를 둘 다 false 로 돌려줘 모름을 구분할 수 없다
    @Override
    public Optional<Boolean> isDirectory(String windowsPath) {
        try {
            return Optional.of(Files.readAttributes(Path.of(windowsPath), BasicFileAttributes.class)
                    .isDirectory());
        } catch (NoSuchFileException e) {
            return Optional.of(false);
        } catch (IOException | InvalidPathException | SecurityException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> readText(String windowsPath) {
        try {
            return Optional.of(new String(Files.readAllBytes(Path.of(windowsPath)), StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return Optional.of("");
        } catch (IOException | InvalidPathException | SecurityException e) {
            return Optional.empty();
        }
    }
}
