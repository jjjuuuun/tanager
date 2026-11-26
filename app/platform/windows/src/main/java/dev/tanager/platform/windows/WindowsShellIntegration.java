package dev.tanager.platform.windows;

import dev.tanager.application.port.out.Integration;
import dev.tanager.application.port.out.ShellIntegration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 클래스패스의 셸 통합 스크립트를 {@code <appData>\shell-integration\} 에 푼다(ADR-0014).
 *
 * <p>버전 파일 대신 바이트를 비교한다 — 같으면 건드리지 않고(수정 시각 유지), 다르면 사용자가 고친 파일이라도 되돌린다. 셸이 반쯤 쓴
 * 파일을 읽지 않도록 같은 디렉터리의 임시 파일에 쓴 뒤 원자적으로 옮긴다.
 */
final class WindowsShellIntegration implements ShellIntegration {
    private static final String DIRECTORY = "shell-integration";
    private static final String RESOURCE_ROOT = "/shell-integration/";
    private static final List<String> SCRIPTS = List.of("tanager.bash", "tanager.ps1");

    private final Path appData;

    WindowsShellIntegration(Path appData) {
        this.appData = Objects.requireNonNull(appData, "appData");
    }

    @Override
    public Integration install() {
        Path dir = appData.resolve(DIRECTORY);
        try {
            Files.createDirectories(dir);
            for (String name : SCRIPTS) {
                installScript(dir.resolve(name), resource(name));
            }
            return new Integration.Installed(dir);
        } catch (IOException | RuntimeException e) {
            // 셸은 통합 없이도 떠야 한다 — 예외 대신 이유를 돌려준다
            return new Integration.Unavailable("셸 통합 스크립트를 " + dir + " 에 설치하지 못했다: " + e);
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = WindowsShellIntegration.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (in == null) {
                throw new IOException("클래스패스에 " + RESOURCE_ROOT + name + " 이 없다");
            }
            return in.readAllBytes();
        }
    }

    private static void installScript(Path target, byte[] content) throws IOException {
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), content)) {
            return;
        }
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }
}
