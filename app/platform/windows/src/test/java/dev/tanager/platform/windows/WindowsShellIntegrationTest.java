package dev.tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.application.port.out.Integration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    private List<String> names() throws IOException {
        try (var names = Files.list(dir())) {
            return names.map(p -> p.getFileName().toString()).toList();
        }
    }

    /** 처음 {@code failures} 번은 AccessDenied 로 실패하고 그 뒤로는 실제로 옮긴다(Windows 에서 대상이 열려 있는 상황). */
    private static final class FlakyMover implements WindowsShellIntegration.Mover {
        private int failures;
        final List<Path> sources = new ArrayList<>();

        FlakyMover(int failures) {
            this.failures = failures;
        }

        @Override
        public void move(Path source, Path target) throws IOException {
            sources.add(source);
            if (failures > 0) {
                failures--;
                throw new AccessDeniedException(target.toString());
            }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 대기 시간만 기록하고 실제로 기다리지 않는다. */
    private static final class RecordingSleeper implements WindowsShellIntegration.Sleeper {
        final List<Duration> sleeps = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            sleeps.add(duration);
        }
    }

    @Test
    @DisplayName("빈 앱 데이터에서 설치하면 두 스크립트가 리소스와 같은 바이트로 생기고 Installed 를 돌려준다")
    void installsIntoEmptyAppData() throws IOException {
        // when
        Integration result = new WindowsShellIntegration(appData).install();

        // then
        assertThat(result).isEqualTo(new Integration.Installed(dir()));
        for (String name : FILES) {
            assertThat(Files.readAllBytes(dir().resolve(name))).isEqualTo(resource(name));
        }
    }

    @Test
    @DisplayName("설치 후 임시 파일이 남지 않는다")
    void leavesNoTempFiles() throws IOException {
        // when
        new WindowsShellIntegration(appData).install();

        // then
        assertThat(names()).containsExactlyInAnyOrder(FILES);
    }

    @Test
    @DisplayName("내용이 같으면 다시 설치해도 수정 시각이 바뀌지 않는다")
    void sameContentKeepsModifiedTime() throws IOException {
        // given
        WindowsShellIntegration installer = new WindowsShellIntegration(appData);
        installer.install();
        for (String name : FILES) {
            Files.setLastModifiedTime(dir().resolve(name), LONG_AGO);
        }

        // when
        Integration result = installer.install();

        // then
        assertThat(result).isEqualTo(new Integration.Installed(dir()));
        for (String name : FILES) {
            assertThat(Files.getLastModifiedTime(dir().resolve(name))).isEqualTo(LONG_AGO);
        }
    }

    @Test
    @DisplayName("내용이 다른 이전 판이 있으면 새 내용으로 바뀐다")
    void staleContentIsReplaced() throws IOException {
        // given
        Files.createDirectories(dir());
        for (String name : FILES) {
            Files.writeString(dir().resolve(name), "# 이전 판\n");
        }

        // when
        Integration result = new WindowsShellIntegration(appData).install();

        // then
        assertThat(result).isEqualTo(new Integration.Installed(dir()));
        for (String name : FILES) {
            assertThat(Files.readAllBytes(dir().resolve(name))).isEqualTo(resource(name));
        }
    }

    @Test
    @DisplayName("한 파일만 이전 판이면 그 파일만 바뀌고 다른 파일의 수정 시각은 그대로다")
    void onlyStaleFileIsRewritten() throws IOException {
        // given
        WindowsShellIntegration installer = new WindowsShellIntegration(appData);
        installer.install();
        Files.setLastModifiedTime(dir().resolve("tanager.bash"), LONG_AGO);
        Files.writeString(dir().resolve("tanager.ps1"), "old");

        // when
        installer.install();

        // then
        assertThat(Files.getLastModifiedTime(dir().resolve("tanager.bash"))).isEqualTo(LONG_AGO);
        assertThat(Files.readAllBytes(dir().resolve("tanager.ps1"))).isEqualTo(resource("tanager.ps1"));
    }

    @Test
    @DisplayName("앱 데이터가 파일이면 예외 없이 Unavailable 을 돌려준다")
    void appDataIsAFile() throws IOException {
        // given
        Path blocked = appData.resolve("blocked");
        Files.writeString(blocked, "파일");

        // when
        Integration result = new WindowsShellIntegration(blocked).install();

        // then
        assertThat(result)
                .isInstanceOfSatisfying(
                        Integration.Unavailable.class,
                        u -> assertThat(u.reason()).isNotBlank());
    }

    @Test
    @DisplayName("shell-integration 이 같은 이름의 파일이면 예외 없이 Unavailable 을 돌려준다")
    void integrationDirIsAFile() throws IOException {
        // given
        Files.writeString(dir(), "파일");

        // when
        Integration result = new WindowsShellIntegration(appData).install();

        // then
        assertThat(result).isInstanceOf(Integration.Unavailable.class);
    }

    @Test
    @DisplayName("스크립트 이름 자리에 디렉터리가 있어 교체할 수 없어도 예외 없이 Unavailable 을 돌려준다")
    void scriptPathIsADirectory() throws IOException {
        // given
        Files.createDirectories(dir().resolve("tanager.bash").resolve("sub"));

        // when
        Integration result = new WindowsShellIntegration(appData).install();

        // then
        assertThat(result).isInstanceOf(Integration.Unavailable.class);
        assertThat(names()).noneMatch(n -> n.endsWith(".tmp"));
    }

    @Test
    @DisplayName("Unavailable 의 이유는 실패한 경로와 원인을 담은 사용자용 문장이고 예외 클래스 이름을 담지 않는다")
    void unavailableReasonIsUserSentence() throws IOException {
        // given
        Path target = dir().resolve("tanager.bash");
        Files.createDirectories(target.resolve("sub"));

        // when
        Integration result = new WindowsShellIntegration(appData).install();

        // then
        assertThat(result)
                .isInstanceOfSatisfying(
                        Integration.Unavailable.class,
                        u -> assertThat(u.reason())
                                .startsWith("셸 통합 스크립트를 설치하지 못했다: " + target + " (")
                                .endsWith(")")
                                .doesNotContain("java.", "Exception:"));
    }

    @Test
    @DisplayName("이전 실행이 남긴 임시 파일이 있어도 설치 뒤 디렉터리에는 두 스크립트만 있다")
    void leftoverTempFilesAreRemoved() throws IOException {
        // given 고정 이름 tmp 와 예전 판의 임의 이름 tmp 가 남아 있다
        Files.createDirectories(dir());
        Files.writeString(dir().resolve("tanager.bash.tmp"), "반쯤 쓴 파일");
        Files.writeString(dir().resolve("tanager.ps1.123456789.tmp"), "반쯤 쓴 파일");

        // when
        Integration result = new WindowsShellIntegration(appData).install();

        // then
        assertThat(result).isEqualTo(new Integration.Installed(dir()));
        assertThat(names()).containsExactlyInAnyOrder(FILES);
    }

    @Test
    @DisplayName("임시 파일은 대상 옆의 <이름>.tmp 하나로 고정된다")
    void tempFileNameIsFixed() throws IOException {
        // given
        FlakyMover mover = new FlakyMover(0);

        // when
        new WindowsShellIntegration(appData, mover, new RecordingSleeper()).install();

        // then
        assertThat(mover.sources).containsExactly(dir().resolve("tanager.bash.tmp"), dir().resolve("tanager.ps1.tmp"));
    }

    @Test
    @DisplayName("교체가 잠깐 거부되면 기다렸다가 다시 시도해 Installed 를 돌려준다")
    void transientMoveFailureIsRetried() throws IOException {
        // given 첫 파일의 교체가 두 번 거부된다
        FlakyMover mover = new FlakyMover(2);
        RecordingSleeper sleeper = new RecordingSleeper();

        // when
        Integration result = new WindowsShellIntegration(appData, mover, sleeper).install();

        // then
        assertThat(result).isEqualTo(new Integration.Installed(dir()));
        assertThat(sleeper.sleeps).hasSize(2).allMatch(d -> d.isPositive() && d.toMillis() <= 500);
        for (String name : FILES) {
            assertThat(Files.readAllBytes(dir().resolve(name))).isEqualTo(resource(name));
        }
        assertThat(names()).containsExactlyInAnyOrder(FILES);
    }

    @Test
    @DisplayName("교체가 계속 거부되면 세 번 다시 시도한 뒤 Unavailable 을 돌려주고 임시 파일을 남기지 않는다")
    void persistentMoveFailureGivesUp() throws IOException {
        // given
        RecordingSleeper sleeper = new RecordingSleeper();
        WindowsShellIntegration.Mover denied = (source, target) -> {
            throw new AccessDeniedException(target.toString());
        };

        // when
        Integration result = new WindowsShellIntegration(appData, denied, sleeper).install();

        // then
        assertThat(result).isInstanceOf(Integration.Unavailable.class);
        assertThat(sleeper.sleeps).hasSize(3);
        assertThat(names()).noneMatch(n -> n.endsWith(".tmp"));
    }

    @Test
    @DisplayName("교체가 실패했어도 다른 인스턴스가 이미 같은 내용을 썼으면 다시 시도하지 않고 Installed 를 돌려준다")
    void moveFailureWithSameContentAlreadyInPlaceIsInstalled() throws IOException {
        // given 옮기는 순간 다른 인스턴스가 먼저 같은 내용을 쓰고, 이쪽 교체는 거부된다
        RecordingSleeper sleeper = new RecordingSleeper();
        WindowsShellIntegration.Mover racing = (source, target) -> {
            Files.write(target, Files.readAllBytes(source));
            throw new AccessDeniedException(target.toString());
        };

        // when
        Integration result = new WindowsShellIntegration(appData, racing, sleeper).install();

        // then
        assertThat(result).isEqualTo(new Integration.Installed(dir()));
        assertThat(sleeper.sleeps).isEmpty();
        assertThat(names()).containsExactlyInAnyOrder(FILES);
    }

    @Property(tries = 20)
    @Label("이전 내용이 무엇이든 설치 후에는 리소스와 같고 두 번째 설치는 아무것도 바꾸지 않는다")
    void anyStaleContentConverges(@ForAll @Size(max = 64) byte[] stale) throws IOException {
        // given
        Path root = Files.createTempDirectory("tanager-integration");
        try {
            Path target = root.resolve("shell-integration");
            Files.createDirectories(target);
            Files.write(target.resolve("tanager.ps1"), stale);
            WindowsShellIntegration installer = new WindowsShellIntegration(root);

            // when
            installer.install();
            Files.setLastModifiedTime(target.resolve("tanager.ps1"), LONG_AGO);
            installer.install();

            // then
            assertThat(Files.readAllBytes(target.resolve("tanager.ps1"))).isEqualTo(resource("tanager.ps1"));
            assertThat(Files.getLastModifiedTime(target.resolve("tanager.ps1"))).isEqualTo(LONG_AGO);
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
