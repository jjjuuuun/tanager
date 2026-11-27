package tanager.platform.windows;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.MonitorItem;

/** 상태줄 항목을 누르면 여는 도구(FR-MON-04, #187). */
class WindowsSystemToolsTest {
    private final List<List<String>> launched = new ArrayList<>();
    private final WindowsSystemTools tools = new WindowsSystemTools(launched::add);

    @Test
    @DisplayName("CPU · 메모리 · WSL 은 작업 관리자, 디스크는 디스크 정리를 start 로 연다(자동 권한 상승 프로그램이라 CreateProcess 로는 못 띄운다)")
    void opensTaskManagerOrDiskCleanup() {
        // when
        tools.open(MonitorItem.CPU);
        tools.open(MonitorItem.MEMORY);
        tools.open(MonitorItem.WSL);
        tools.open(MonitorItem.DISK);

        // then
        List<String> taskmgr = List.of("cmd.exe", "/c", "start", "Tanager tool", "taskmgr.exe");
        assertThat(launched)
                .containsExactly(
                        taskmgr, taskmgr, taskmgr, List.of("cmd.exe", "/c", "start", "Tanager tool", "cleanmgr.exe"));
    }
}
