package tanager.application.port.out;

/** 상태줄에 보일 리소스 항목(config {@code monitor.items}, SDD §9.3 · FR-MON). */
public enum MonitorItem {
    CPU,
    MEMORY,
    DISK,
    /** WSL VM 의 메모리 · 디스크. */
    WSL
}
