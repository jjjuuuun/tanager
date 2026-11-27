package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WindowsPlatformDirsTest {

    private static WindowsPlatformModule module(Function<String, Optional<String>> env) {
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        return new WindowsPlatformModule(catalog, env);
    }

    @Test
    @DisplayName("appData 는 APPDATA 아래 Tanager 폴더다")
    void appDataIsUnderAppdataEnv() {
        // given
        WindowsPlatformModule module = module(
                key -> key.equals("APPDATA") ? Optional.of("C:\\Users\\me\\AppData\\Roaming") : Optional.empty());

        // then
        assertThat(module.dirs().appData()).isEqualTo(Path.of("C:\\Users\\me\\AppData\\Roaming", "Tanager"));
    }

    @Test
    @DisplayName("APPDATA 가 없으면 user.home 의 AppData/Roaming/Tanager 로 대체한다")
    void appDataFallsBackToUserHome() {
        // given
        WindowsPlatformModule module = module(key -> Optional.empty());

        // then
        assertThat(module.dirs().appData())
                .isEqualTo(Path.of(System.getProperty("user.home"), "AppData", "Roaming", "Tanager"));
    }

    @Test
    @DisplayName("dirs 는 디렉터리를 만들지 않는다")
    void dirsDoesNotCreateDirectories() {
        // given
        Path bogus = Path.of(System.getProperty("java.io.tmpdir"), "tanager-no-such-appdata-" + System.nanoTime());

        // when
        Path appData = module(key -> key.equals("APPDATA") ? Optional.of(bogus.toString()) : Optional.empty())
                .dirs()
                .appData();

        // then
        assertThat(appData).isEqualTo(bogus.resolve("Tanager"));
        assertThat(bogus).doesNotExist();
    }
}
