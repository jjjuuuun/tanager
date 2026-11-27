package tanager.ui.status;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tanager.application.port.in.ResourceReport;
import tanager.application.port.out.MonitorItem;
import tanager.application.port.out.ProbeError;
import tanager.domain.monitor.DiskUsage;
import tanager.domain.monitor.ResourceSample;
import tanager.domain.monitor.Usage;
import tanager.domain.monitor.UsageLevel;
import tanager.domain.monitor.WslUsage;
import tanager.ui.i18n.Messages;
import tanager.ui.i18n.Msg;

/**
 * 리소스 보고 → 상태줄 항목(순수, FR-MON-01~03, #188). 퍼센트는 정수, GB · MB 는 소수 한 자리다. 단위는 전체 크기로 고른다(1 GB 미만이면
 * MB). 1 GB = 2^30 바이트 — 작업 관리자 · 탐색기와 같은 값이 나온다.
 */
final class ResourceText {
    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024L * MIB;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ResourceText() {}

    /** 보일 항목들(CPU → 메모리 → 디스크 → WSL 순). 끈 항목과 표본에 없는 항목은 빠진다. {@code zone} 은 마지막 갱신 시각을 보일 시간대다. */
    static List<ItemText> format(ResourceReport report, Locale locale, ZoneId zone) {
        return new Writer(Messages.of(locale), report.sample().at(), zone).items(report);
    }

    private record Amount(String used, String total, String available, String unit) {}

    private record Writer(Messages messages, Instant at, ZoneId zone) {

        String t(Msg msg, Object... args) {
            return messages.text(msg, args);
        }

        List<ItemText> items(ResourceReport report) {
            ResourceSample sample = report.sample();
            List<ItemText> items = new ArrayList<>();
            if (report.items().contains(MonitorItem.CPU)) {
                items.add(cpu(sample, report.levels().cpu()));
            }
            if (report.items().contains(MonitorItem.MEMORY)) {
                items.add(memory(sample.memory(), report.levels().memory()));
            }
            if (report.items().contains(MonitorItem.DISK) && sample.disk().isPresent()) {
                items.add(disk(sample.disk().get(), level(report.levels().disk())));
            }
            if (report.items().contains(MonitorItem.WSL)) {
                report.wslUnavailable().ifPresent(error -> items.add(wslUnavailable(error)));
                sample.wsl().ifPresent(wsl -> {
                    items.add(wslMemory(wsl, level(report.levels().wslMemory())));
                    items.add(wslDisk(wsl, level(report.levels().wslDisk())));
                });
            }
            return List.copyOf(items);
        }

        ItemText cpu(ResourceSample sample, UsageLevel level) {
            String text = t(Msg.STATUS_CPU, Math.round(sample.cpuLoad() * 100));
            return item(
                    MonitorItem.CPU,
                    text,
                    level,
                    Optional.empty(),
                    List.of(t(Msg.STATUS_TIP_CORES, text, sample.cores())));
        }

        ItemText memory(Usage memory, UsageLevel level) {
            Amount a = amount(memory);
            String text = t(Msg.STATUS_MEMORY, a.used(), a.total(), a.unit());
            return item(MonitorItem.MEMORY, text, level, Optional.of(memory.ratio()), List.of(text, available(a)));
        }

        ItemText disk(DiskUsage disk, UsageLevel level) {
            Amount a = amount(disk.usage());
            String text = t(Msg.STATUS_DISK, driveName(disk.root()), a.used(), a.total(), a.unit());
            List<String> lines = List.of(t(Msg.STATUS_TIP_DRIVE, disk.root()), text, available(a));
            return item(MonitorItem.DISK, text, level, Optional.empty(), lines);
        }

        ItemText wslMemory(WslUsage wsl, UsageLevel level) {
            Amount a = amount(wsl.vmMemory());
            String text = t(Msg.STATUS_WSL_MEMORY, wsl.distro(), a.used(), a.total(), a.unit());
            List<String> lines = List.of(t(Msg.STATUS_TIP_WSL_MEMORY, wsl.distro()), text, available(a));
            return item(MonitorItem.WSL, text, level, Optional.empty(), lines);
        }

        ItemText wslDisk(WslUsage wsl, UsageLevel level) {
            Amount a = amount(wsl.rootDisk());
            String text = t(Msg.STATUS_WSL_DISK, a.used(), a.total(), a.unit());
            List<String> lines = List.of(t(Msg.STATUS_TIP_WSL_DISK, wsl.distro()), text, available(a));
            return item(MonitorItem.WSL, text, level, Optional.empty(), lines);
        }

        ItemText wslUnavailable(ProbeError error) {
            String reason = t(Msg.STATUS_WSL_UNAVAILABLE, reason(error));
            return item(MonitorItem.WSL, t(Msg.STATUS_WSL_NONE), UsageLevel.NORMAL, Optional.empty(), List.of(reason));
        }

        String reason(ProbeError error) {
            return switch (error) {
                case ProbeError.TimedOut(var after) -> t(Msg.STATUS_WSL_TIMED_OUT, after.toMillis());
                case ProbeError.Failed(String reason) -> t(Msg.STATUS_WSL_FAILED, reason);
                case ProbeError.Unreadable(String reason) -> t(Msg.STATUS_WSL_UNREADABLE, reason);
                case ProbeError.Unsupported() -> t(Msg.STATUS_WSL_UNSUPPORTED);
            };
        }

        // 항목마다 붙는 줄: 경고 · 마지막 갱신 · 누르기 안내
        ItemText item(MonitorItem item, String text, UsageLevel level, Optional<Double> bar, List<String> details) {
            List<String> lines = new ArrayList<>(details);
            if (level == UsageLevel.WARN) {
                lines.add(t(Msg.STATUS_TIP_WARN));
            }
            lines.add(t(Msg.STATUS_TIP_UPDATED, TIME.format(at.atZone(zone))));
            lines.add(t(Msg.STATUS_TIP_CLICK));
            return new ItemText(item, text, level, bar, lines);
        }

        String available(Amount a) {
            return t(Msg.STATUS_TIP_AVAILABLE, a.available(), a.unit());
        }

        Amount amount(Usage usage) {
            boolean gigabytes = usage.total() >= GIB;
            long unit = gigabytes ? GIB : MIB;
            return new Amount(
                    number(usage.used(), unit),
                    number(usage.total(), unit),
                    number(usage.total() - usage.used(), unit),
                    t(gigabytes ? Msg.STATUS_UNIT_GB : Msg.STATUS_UNIT_MB));
        }
    }

    private static UsageLevel level(Optional<UsageLevel> level) {
        return level.orElse(UsageLevel.NORMAL);
    }

    private static String number(long bytes, long unit) {
        return String.format(Locale.ROOT, "%.1f", (double) bytes / unit);
    }

    // C:\ → C: (상태줄은 짧게, 툴팁은 경로 그대로). WSL 의 / 는 그대로
    private static String driveName(String root) {
        return root.length() > 1 && root.endsWith("\\") ? root.substring(0, root.length() - 1) : root;
    }
}
