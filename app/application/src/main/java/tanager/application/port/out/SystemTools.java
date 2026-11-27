package tanager.application.port.out;

/**
 * 상태줄 항목을 누르면 여는 OS 도구({@link PlatformModule#systemTools()}, FR-MON-04). Windows: CPU · 메모리 · WSL → 작업 관리자, 디스크 →
 * 디스크 정리.
 */
@FunctionalInterface
public interface SystemTools {

    /** {@code item} 에 맞는 도구를 띄운다. 끝나기를 기다리지 않는다. 띄우지 못해도 던지지 않는다(로그만). EDT 에서 부르지 않는다. */
    void open(MonitorItem item);
}
