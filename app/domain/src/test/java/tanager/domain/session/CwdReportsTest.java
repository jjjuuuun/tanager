package tanager.domain.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.PathError;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;

class CwdReportsTest {

    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("U");
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();

    @Test
    @DisplayName("OSC 7 보고는 WSL 환경에서 '..' 이 풀린 WorkspacePath 가 된다")
    void fileUri_toWslPath_normalized() {
        // given
        CwdReport report = new CwdReport.FileUri("host", "/mnt/c/a/../b");
        // when
        Result<WorkspacePath, PathError> result = CwdReports.toPath(report, WSL);
        // then
        assertThat(result.value())
                .hasValue(WorkspacePath.of(WSL, "/mnt/c/b").value().orElseThrow());
        assertThat(result.value().orElseThrow().path()).isEqualTo("/mnt/c/b");
    }

    @Test
    @DisplayName("OSC 9;9 보고는 Windows 환경에서 WorkspacePath 가 된다")
    void windowsPath_toWindowsPath() {
        Result<WorkspacePath, PathError> result =
                CwdReports.toPath(new CwdReport.WindowsPath("C:\\Users\\user\\한글 폴더"), WIN);
        assertThat(result.value().orElseThrow().path()).isEqualTo("C:\\Users\\user\\한글 폴더");
        assertThat(result.value().orElseThrow().env()).isEqualTo(WIN);
    }

    @Test
    @DisplayName("OSC 9;9 보고를 WSL 환경으로 해석하면 PathError 다")
    void windowsPath_inWsl_isError() {
        Result<WorkspacePath, PathError> result = CwdReports.toPath(new CwdReport.WindowsPath("C:\\work"), WSL);
        assertThat(result.isOk()).isFalse();
        assertThat(result.error()).isPresent();
    }

    @Test
    @DisplayName("OSC 7 보고를 Windows 환경으로 해석하면 PathError 다")
    void fileUri_inWindows_isError() {
        Result<WorkspacePath, PathError> result = CwdReports.toPath(new CwdReport.FileUri("h", "/tmp"), WIN);
        assertThat(result.isOk()).isFalse();
        assertThat(result.error()).isPresent();
    }

    @Test
    @DisplayName("절대 경로가 아닌 OSC 9;9 본문은 PathError 다")
    void windowsPath_relative_isError() {
        Result<WorkspacePath, PathError> result = CwdReports.toPath(new CwdReport.WindowsPath("work\\dir"), WIN);
        assertThat(result.error()).containsInstanceOf(PathError.NotAbsolute.class);
    }

    @Property
    @Label("OSC 7 경로를 WSL 로 해석한 결과는 WorkspacePath.of 와 같다")
    void fileUri_matchesWorkspacePathOf(@ForAll("wslPaths") String path) {
        Result<WorkspacePath, PathError> viaReport = CwdReports.toPath(new CwdReport.FileUri("h", path), WSL);
        assertThat(viaReport.value()).isEqualTo(WorkspacePath.of(WSL, path).value());
    }

    @Provide
    Arbitrary<String> wslPaths() {
        return Arbitraries.of("a", "b", "..", ".", "한글")
                .list()
                .ofMaxSize(6)
                .map(parts -> "/" + String.join("/", parts));
    }

    @Test
    @DisplayName("OSC 7 의 퍼센트 디코딩 결과에 큰따옴표가 있으면 Err 가 된다")
    void fileUri_decodedQuote_isRejected() {
        // given 셸이 보낸 그대로의 바이트(경로의 " 는 %22 로 인코딩된다)를 스캐너가 디코딩한 보고
        byte[] bytes = "\u001b]7;file://host/tmp/a%22b\u001b\\".getBytes(StandardCharsets.UTF_8);
        List<CwdReport> reports = OscScanner.initial().feed(bytes).reports();
        assertThat(reports).containsExactly(new CwdReport.FileUri("host", "/tmp/a\"b"));
        // when
        Result<WorkspacePath, PathError> result = CwdReports.toPath(reports.getFirst(), WSL);
        // then
        assertThat(result.error()).containsInstanceOf(PathError.UnsafeCharacter.class);
    }
}
