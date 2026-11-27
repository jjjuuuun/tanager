package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.platform.OperatingSystem;
import tanager.application.port.out.Integration;
import tanager.application.port.out.PathTranslation;
import tanager.application.port.out.PlatformModule;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ShellProfile;

class WindowsPlatformModuleTest {
    private static final ShellEnvironment WINDOWS = new ShellEnvironment.Windows();
    private final WindowsPlatformModule module = new WindowsPlatformModule();

    @Test
    @DisplayName("Windows 계열 OS 이름만 지원한다")
    void supportsOnlyWindows() {
        assertThat(module.supports(new OperatingSystem("Windows 11", "10.0", "amd64")))
                .isTrue();
        assertThat(module.supports(new OperatingSystem("Windows 10", "10.0", "amd64")))
                .isTrue();
        assertThat(module.supports(new OperatingSystem("Linux", "6.6", "amd64")))
                .isFalse();
        assertThat(module.supports(new OperatingSystem("Mac OS X", "15.0", "aarch64")))
                .isFalse();
    }

    @Test
    @DisplayName("id 는 windows")
    void idIsWindows() {
        assertThat(module.id()).isEqualTo("windows");
    }

    @Test
    @DisplayName("ServiceLoader 로 찾을 수 있게 등록돼 있다")
    void isRegisteredAsService() {
        assertThat(ServiceLoader.load(PlatformModule.class).stream().map(ServiceLoader.Provider::type))
                .contains(WindowsPlatformModule.class);
    }

    @Test
    @DisplayName("shells() 는 catalog 에 위임한다")
    void shellsDelegatesToCatalog() {
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        assertThat(new WindowsPlatformModule(catalog, key -> Optional.empty()).shells())
                .isEqualTo(catalog.shells());
    }

    @Test
    @Tag("windows")
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("실제 Windows 에서 powershell 과 cmd 가 있고 실행 파일이 디스크에 있다")
    void realShellsExist() {
        List<ShellProfile> shells = new WindowsPlatformModule().shells();
        assertThat(shells).extracting(p -> p.id().value()).contains("powershell", "cmd");
        shells.stream()
                .filter(p ->
                        p.id().value().equals("powershell") || p.id().value().equals("cmd"))
                .forEach(p -> assertThat(Files.exists(Path.of(p.launch().executable())))
                        .as(p.launch().executable())
                        .isTrue());
    }

    @Test
    @DisplayName("integration() 은 APPDATA 아래 Tanager/shell-integration 에 스크립트를 설치한다")
    void integrationInstallsUnderAppData(@TempDir Path appdata) {
        // given
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        WindowsPlatformModule custom = new WindowsPlatformModule(
                catalog, key -> "APPDATA".equals(key) ? Optional.of(appdata.toString()) : Optional.empty());

        // when
        Integration result = custom.integration().install();

        // then
        Path expected = appdata.resolve("Tanager").resolve("shell-integration");
        assertThat(result).isEqualTo(new Integration.Installed(expected));
        assertThat(expected.resolve("tanager.ps1")).isRegularFile();
        assertThat(expected.resolve("tanager.bash")).isRegularFile();
    }

    @Test
    @DisplayName("paths() 는 WSL 기본 마운트(/mnt/)로 /mnt/c/x ↔ C:\\x 를 바꾼다(#90)")
    void pathsUseDefaultMount() {
        // given
        var mnt = tanager.domain.history.WorkspacePath.of(
                        new tanager.domain.history.ShellEnvironment.Wsl("U"), "/mnt/c/x")
                .value()
                .orElseThrow();

        // when
        var windows = new WindowsPlatformModule()
                .paths()
                .translate(mnt, new tanager.domain.history.ShellEnvironment.Windows());

        // then
        org.assertj.core.api.Assertions.assertThat(windows.map(tanager.domain.history.WorkspacePath::path))
                .contains("C:\\x");
    }

    @Test
    @DisplayName("paths() 는 directories() 가 배포판에서 읽어 둔 마운트 루트로 /win/c/x ↔ C:\\x 를 바꾼다(#53)")
    void pathsUseMountRootLearnedByDirectories() {
        // given
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        FakeWindowsFiles files = new FakeWindowsFiles()
                .directory("\\\\wsl.localhost\\Ubuntu\\home\\me", Optional.of(true))
                .text("\\\\wsl.localhost\\Ubuntu\\etc\\wsl.conf", Optional.of("[automount]\nroot = /win/\n"));
        WindowsPlatformModule custom = new WindowsPlatformModule(
                catalog,
                key -> Optional.empty(),
                "C:\\Users\\u",
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                files,
                Runnable::run);
        ShellEnvironment ubuntu = new ShellEnvironment.Wsl("Ubuntu");
        // 미리 받아 둔 변환기도 나중에 알게 된 루트를 쓴다(bootstrap 은 paths() 를 한 번만 부른다)
        PathTranslation paths = custom.paths();

        // when
        var home = custom.directories()
                .isDirectory(WorkspacePath.of(ubuntu, "/home/me").value().orElseThrow(), Duration.ofSeconds(1));
        var toWindows =
                paths.translate(WorkspacePath.of(ubuntu, "/win/c/x").value().orElseThrow(), WINDOWS);
        var toWsl = paths.translate(WorkspacePath.of(WINDOWS, "C:\\x").value().orElseThrow(), ubuntu);

        // then
        assertThat(home).contains(true);
        assertThat(toWindows.map(WorkspacePath::path)).contains("C:\\x");
        assertThat(toWsl.map(WorkspacePath::path)).contains("/win/c/x");
    }

    @Test
    @DisplayName("appearance() 는 레지스트리의 다크/라이트(#184)와 '애니메이션 효과'(#152) 설정을 읽는다")
    void appearanceReadsThemeAndAnimationSettings() {
        // given
        WindowsShellCatalog catalog = new WindowsShellCatalog(
                new FakeCommandRunner(new CommandOutcome.NotStarted("x")),
                key -> Optional.empty(),
                Duration.ofSeconds(1));
        FakeCommandRunner reg = new FakeCommandRunner(new CommandOutcome.Exited(
                0,
                ("    MinAnimate    REG_SZ    0\r\n    AppsUseLightTheme    REG_DWORD    0x1\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        WindowsPlatformModule custom = new WindowsPlatformModule(catalog, key -> Optional.empty(), "C:\\Users\\u", reg);

        // when
        var appearance = custom.appearance();

        // then
        assertThat(appearance.animations()).isFalse();
        assertThat(appearance.theme()).contains(tanager.application.port.out.Theme.LIGHT);
        assertThat(reg.commands()).hasSize(2);
        assertThat(reg.commands()).allSatisfy(command -> assertThat(command).startsWith("reg.exe", "query"));
    }
}
