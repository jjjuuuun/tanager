package tanager.ui.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.ResourceReport;
import tanager.application.port.out.MonitorItem;
import tanager.application.port.out.ProbeError;
import tanager.domain.monitor.DiskUsage;
import tanager.domain.monitor.ResourceLevels;
import tanager.domain.monitor.ResourceSample;
import tanager.domain.monitor.Usage;
import tanager.domain.monitor.UsageLevel;
import tanager.domain.monitor.WslUsage;

/** 상태줄 리소스 항목의 글자 · 경고 단계 · 툴팁(순수, FR-MON-01~03, #188). */
class ResourceTextTest {
    static final long GIB = 1024L * 1024 * 1024;
    static final long MIB = 1024L * 1024;
    private static final ZoneId UTC = ZoneOffset.UTC;
    // 1970-01-01 14:03:21 UTC
    static final Instant AT = Instant.ofEpochSecond(14 * 3600 + 3 * 60 + 21);

    static Usage gb(double used, double total) {
        return new Usage(Math.round(used * GIB), Math.round(total * GIB));
    }

    static ResourceSample host(double cpu, Usage memory) {
        return new ResourceSample(AT, cpu, 8, memory, Optional.empty(), Optional.empty());
    }

    static ResourceLevels normal() {
        return new ResourceLevels(
                UsageLevel.NORMAL, UsageLevel.NORMAL, Optional.empty(), Optional.empty(), Optional.empty());
    }

    static ResourceReport report(ResourceSample sample, ResourceLevels levels, Set<MonitorItem> items) {
        return new ResourceReport(sample, levels, items, Optional.empty());
    }

    static ResourceReport full() {
        ResourceSample sample = new ResourceSample(
                AT,
                0.23,
                8,
                gb(9.1, 31.8),
                Optional.of(new DiskUsage("C:\\", gb(210, 476))),
                Optional.of(new WslUsage("Ubuntu", gb(3.2, 16), gb(41, 1007))));
        ResourceLevels levels = new ResourceLevels(
                UsageLevel.NORMAL,
                UsageLevel.NORMAL,
                Optional.of(UsageLevel.NORMAL),
                Optional.of(UsageLevel.NORMAL),
                Optional.of(UsageLevel.NORMAL));
        return report(sample, levels, EnumSet.allOf(MonitorItem.class));
    }

    private static List<String> texts(List<ItemText> items) {
        return items.stream().map(ItemText::text).toList();
    }

    private static ItemText only(List<ItemText> items, MonitorItem item) {
        List<ItemText> found = items.stream().filter(i -> i.item() == item).toList();
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    @Test
    @DisplayName("CPU 는 정수 퍼센트, 메모리는 사용 / 전체 GB 소수 한 자리다 — 한국어 · 영어")
    void cpuAndMemoryText() {
        // given
        ResourceReport report =
                report(host(0.23, gb(9.1, 31.8)), normal(), EnumSet.of(MonitorItem.CPU, MonitorItem.MEMORY));

        // when
        List<ItemText> ko = ResourceText.format(report, Locale.KOREA, UTC);
        List<ItemText> en = ResourceText.format(report, Locale.US, UTC);

        // then
        assertThat(texts(ko)).containsExactly("CPU 23%", "메모리 9.1 / 31.8 GB");
        assertThat(texts(en)).containsExactly("CPU 23%", "Memory 9.1 / 31.8 GB");
    }

    @Test
    @DisplayName("메모리가 WARN 이면 그 항목만 WARN 이고 막대는 사용 비율이며, 툴팁에 사용 가능 메모리가 있다")
    void memoryWarn() {
        // given
        ResourceLevels levels = new ResourceLevels(
                UsageLevel.NORMAL, UsageLevel.WARN, Optional.empty(), Optional.empty(), Optional.empty());
        ResourceReport report = report(host(0.23, gb(28, 32)), levels, EnumSet.of(MonitorItem.CPU, MonitorItem.MEMORY));

        // when
        List<ItemText> items = ResourceText.format(report, Locale.KOREA, UTC);

        // then
        ItemText memory = only(items, MonitorItem.MEMORY);
        assertThat(memory.level()).isEqualTo(UsageLevel.WARN);
        assertThat(memory.bar()).hasValueSatisfying(ratio -> assertThat(ratio).isEqualTo(0.875));
        assertThat(memory.tooltip()).contains("사용 가능 4.0 GB", "임계값을 넘었다");
        assertThat(only(items, MonitorItem.CPU).level()).isEqualTo(UsageLevel.NORMAL);
        assertThat(only(items, MonitorItem.CPU).bar()).isEmpty();
    }

    @Test
    @DisplayName("툴팁: CPU 는 코어 수, 모든 항목은 마지막 갱신 시각(현지 시각)을 보인다")
    void tooltipDetails() {
        // when
        List<ItemText> items = ResourceText.format(full(), Locale.KOREA, UTC);

        // then
        assertThat(only(items, MonitorItem.CPU).tooltip()).contains("CPU 23% · 코어 8개");
        for (ItemText item : items) {
            assertThat(item.tooltip()).as(item.text()).contains("마지막 갱신 14:03:21");
            assertThat(item.tooltip()).as(item.text()).doesNotContain("임계값을 넘었다");
        }
    }

    @Test
    @DisplayName("디스크는 드라이브 이름과 사용 / 전체, 툴팁에 드라이브 경로와 사용 가능 용량을 보인다")
    void diskText() {
        // when
        ItemText disk = only(ResourceText.format(full(), Locale.KOREA, UTC), MonitorItem.DISK);

        // then
        assertThat(disk.text()).isEqualTo("C: 210.0 / 476.0 GB");
        assertThat(disk.tooltip()).contains("드라이브 C:\\", "사용 가능 266.0 GB");
        assertThat(disk.bar()).isEmpty();
    }

    @Test
    @DisplayName("포커스 창이 WSL 이면 VM 메모리와 배포판 / 디스크 두 항목이 순서대로 나온다")
    void wslItems() {
        // when
        List<ItemText> items = ResourceText.format(full(), Locale.KOREA, UTC);

        // then
        assertThat(texts(items))
                .containsExactly(
                        "CPU 23%",
                        "메모리 9.1 / 31.8 GB", "C: 210.0 / 476.0 GB", "Ubuntu 3.2 / 16.0 GB", "/ 41.0 / 1007.0 GB");
        List<ItemText> wsl =
                items.stream().filter(i -> i.item() == MonitorItem.WSL).toList();
        assertThat(wsl.getFirst().tooltip()).contains("WSL VM 메모리 (Ubuntu)", "사용 가능 12.8 GB");
        assertThat(wsl.getLast().tooltip()).contains("WSL Ubuntu 의 / 디스크");
    }

    @Test
    @DisplayName("포커스 창이 WSL 이 아니면(WSL 표본 · 이유 모두 없음) WSL 항목이 없다")
    void noWslWhenNotFocused() {
        // given
        ResourceReport report = report(host(0.5, gb(1, 2)), normal(), EnumSet.allOf(MonitorItem.class));

        // when
        List<ItemText> items = ResourceText.format(report, Locale.KOREA, UTC);

        // then: 디스크도 표본에 없으면(경로를 모름) 보이지 않는다
        assertThat(items).extracting(ItemText::item).containsExactly(MonitorItem.CPU, MonitorItem.MEMORY);
    }

    @Test
    @DisplayName("WSL 응답이 없으면 'WSL —' 한 항목이고 툴팁에 이유가 보인다")
    void wslUnavailable() {
        // given
        ResourceReport report = new ResourceReport(
                host(0.5, gb(1, 2)),
                normal(),
                EnumSet.allOf(MonitorItem.class),
                Optional.of(new ProbeError.TimedOut(Duration.ofSeconds(1))));

        // when
        List<ItemText> ko = ResourceText.format(report, Locale.KOREA, UTC);
        List<ItemText> en = ResourceText.format(report, Locale.US, UTC);

        // then
        ItemText wsl = only(ko, MonitorItem.WSL);
        assertThat(wsl.text()).isEqualTo("WSL —");
        assertThat(wsl.level()).isEqualTo(UsageLevel.NORMAL);
        assertThat(wsl.tooltip()).contains("WSL 지표를 읽지 못했다: 1000ms 안에 응답이 없다");
        assertThat(only(en, MonitorItem.WSL).tooltip())
                .contains("Could not read WSL metrics: no response within 1000 ms");
    }

    @Test
    @DisplayName("WSL 을 읽지 못한 이유마다 화면 문장이 있다")
    void wslReasons() {
        // given
        List<ProbeError> errors = List.of(
                new ProbeError.Failed("exit 1"),
                new ProbeError.Unreadable("no MemTotal"),
                new ProbeError.Unsupported());

        // when
        List<String> reasons = errors.stream()
                .map(error -> only(
                                ResourceText.format(
                                        new ResourceReport(
                                                host(0.5, gb(1, 2)),
                                                normal(),
                                                EnumSet.of(MonitorItem.WSL),
                                                Optional.of(error)),
                                        Locale.KOREA,
                                        UTC),
                                MonitorItem.WSL)
                        .tooltip()
                        .getFirst())
                .toList();

        // then
        assertThat(reasons)
                .containsExactly(
                        "WSL 지표를 읽지 못했다: 명령이 실패했다 (exit 1)",
                        "WSL 지표를 읽지 못했다: 출력을 읽을 수 없다 (no MemTotal)",
                        "WSL 지표를 읽지 못했다: 이 OS 에는 WSL 이 없다");
    }

    @Test
    @DisplayName("끈 항목은 값이 있어도 나오지 않는다")
    void hiddenItems() {
        // given
        ResourceReport report = new ResourceReport(
                full().sample(), full().levels(), EnumSet.of(MonitorItem.MEMORY, MonitorItem.DISK), Optional.empty());

        // when
        List<ItemText> items = ResourceText.format(report, Locale.KOREA, UTC);

        // then
        assertThat(items).extracting(ItemText::item).containsExactly(MonitorItem.MEMORY, MonitorItem.DISK);
    }

    @Test
    @DisplayName("전체가 1 GB 보다 작으면 MB 로, 소수 한 자리다")
    void megabytes() {
        // given
        Usage small = new Usage(100 * MIB, 512 * MIB);
        ResourceReport report = report(host(0.004, small), normal(), EnumSet.of(MonitorItem.CPU, MonitorItem.MEMORY));

        // when
        List<ItemText> items = ResourceText.format(report, Locale.US, UTC);

        // then: 0.4% 는 0% 로 반올림한다
        assertThat(texts(items)).containsExactly("CPU 0%", "Memory 100.0 / 512.0 MB");
    }

    @Test
    @DisplayName("툴팁 마지막 줄은 누르기 · 우클릭 안내다")
    void clickHint() {
        // when
        List<ItemText> items = ResourceText.format(full(), Locale.KOREA, UTC);

        // then
        for (ItemText item : items) {
            assertThat(item.tooltip().getLast()).isEqualTo("누르면 시스템 도구를 연다 · 우클릭하면 항목을 끄고 켠다");
        }
    }
}
