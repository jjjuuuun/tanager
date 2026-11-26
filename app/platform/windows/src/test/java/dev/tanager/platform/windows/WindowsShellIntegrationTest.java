package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.port.out.Integration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 설치기 계약(ADR-0014). OS 무관하게 돈다 — appData 를 주입한다. */
class WindowsShellIntegrationTest {
    private static final String[] FILES = {"tanager.bash", "tanager.ps1"};
    private static final FileTime LONG_AGO = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));

    @TempDir
    Path appData;

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = WindowsShellIntegrationTest.class.getResourceAsStream("/shell-integration/" + name)) {
            assertThat(in).as("리소스 " + name).isNotNull();
            return in.readAllBytes();
        }
    }

    private Path dir() {
        return appData.resolve("shell-integration");
    }

    @Test
    @DisplayName("빈 앱 데이터에서 설치하면 두 스크립트가 리소스와 같은 바이트로 생기고 Installed 를 돌려준다")
    void installsIntoEmptyAppData() throws IOException {
        Integration result = new WindowsShellIntegration(appData).install();

        assertThat(result).isEqualTo(new Integration.Installed(dir()));
        for (String name : FILES) {
            assertThat(Files.readAllBytes(dir().resolve(name))).isEqualTo(resource(name));
        }
    }

    @Test
    @DisplayName("설치 후 임시 파일이 남지 않는다")
    void leavesNoTempFiles() throws IOException {
        new WindowsShellIntegration(appData).install();

        try (var names = Files.list(dir())) {
            assertThat(names.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder(FILES);
        }
    }

    @Test
    @DisplayName("내용이 같으면 다시 설치해도 수정 시각이 바뀌지 않는다")
    void sameContentKeepsModifiedTime() throws IOException {
        WindowsShellIntegration installer = new WindowsShellIntegration(appData);
        installer.install();
        for (String name : FILES) {
            Files.setLastModifiedTime(dir().resolve(name), LONG_AGO);
        }

        Integration result = installer.install();

        assertThat(result).isEqualTo(new Integration.Installed(dir()));
        for (String name : FILES) {
            assertThat(Files.getLastModifiedTime(dir().resolve(name))).isEqualTo(LONG_AGO);
        }
    }

    @Test
    @DisplayName("내용이 다른 이전 판이 있으면 새 내용으로 바뀐다")
    void staleContentIsReplaced() throws IOException {
        Files.createDirectories(dir());
        for (String name : FILES) {
            Files.writeString(dir().resolve(name), "# 이전 판\n");
        }

        Integration result = new WindowsShellIntegration(appData).install();

        assertThat(result).isEqualTo(new Integration.Installed(dir()));
        for (String name : FILES) {
            assertThat(Files.readAllBytes(dir().resolve(name))).isEqualTo(resource(name));
        }
    }

    @Test
    @DisplayName("한 파일만 이전 판이면 그 파일만 바뀌고 다른 파일의 수정 시각은 그대로다")
    void onlyStaleFileIsRewritten() throws IOException {
        WindowsShellIntegration installer = new WindowsShellIntegration(appData);
        installer.install();
        Files.setLastModifiedTime(dir().resolve("tanager.bash"), LONG_AGO);
        Files.writeString(dir().resolve("tanager.ps1"), "old");

        installer.install();

        assertThat(Files.getLastModifiedTime(dir().resolve("tanager.bash"))).isEqualTo(LONG_AGO);
        assertThat(Files.readAllBytes(dir().resolve("tanager.ps1"))).isEqualTo(resource("tanager.ps1"));
    }

    @Test
    @DisplayName("앱 데이터가 파일이면 예외 없이 Unavailable 을 돌려준다")
    void appDataIsAFile() throws IOException {
        Path blocked = appData.resolve("blocked");
        Files.writeString(blocked, "파일");

        Integration result = new WindowsShellIntegration(blocked).install();

        assertThat(result)
                .isInstanceOfSatisfying(
                        Integration.Unavailable.class,
                        u -> assertThat(u.reason()).isNotBlank());
    }

    @Test
    @DisplayName("shell-integration 이 같은 이름의 파일이면 예외 없이 Unavailable 을 돌려준다")
    void integrationDirIsAFile() throws IOException {
        Files.writeString(dir(), "파일");

        Integration result = new WindowsShellIntegration(appData).install();

        assertThat(result).isInstanceOf(Integration.Unavailable.class);
    }

    @Test
    @DisplayName("스크립트 이름 자리에 디렉터리가 있어 교체할 수 없어도 예외 없이 Unavailable 을 돌려준다")
    void scriptPathIsADirectory() throws IOException {
        Files.createDirectories(dir().resolve("tanager.bash").resolve("sub"));

        Integration result = new WindowsShellIntegration(appData).install();

        assertThat(result).isInstanceOf(Integration.Unavailable.class);
    }

    @Property(tries = 20)
    @Label("이전 내용이 무엇이든 설치 후에는 리소스와 같고 두 번째 설치는 아무것도 바꾸지 않는다")
    void anyStaleContentConverges(@ForAll @Size(max = 64) byte[] stale) throws IOException {
        Path root = Files.createTempDirectory("tanager-integration");
        try {
            Path target = root.resolve("shell-integration");
            Files.createDirectories(target);
            Files.write(target.resolve("tanager.ps1"), stale);
            WindowsShellIntegration installer = new WindowsShellIntegration(root);

            installer.install();
            Files.setLastModifiedTime(target.resolve("tanager.ps1"), LONG_AGO);
            installer.install();

            assertThat(Files.readAllBytes(target.resolve("tanager.ps1"))).isEqualTo(resource("tanager.ps1"));
            assertThat(Files.getLastModifiedTime(target.resolve("tanager.ps1"))).isEqualTo(LONG_AGO);
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
    }
}
