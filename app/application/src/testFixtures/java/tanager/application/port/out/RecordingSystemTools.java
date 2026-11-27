package tanager.application.port.out;

import java.util.ArrayList;
import java.util.List;

/** 연 도구를 기록만 하는 {@link SystemTools} Fake. 스레드 안전. */
public final class RecordingSystemTools implements SystemTools {
    private final List<MonitorItem> opened = new ArrayList<>();

    @Override
    public synchronized void open(MonitorItem item) {
        opened.add(item);
    }

    /** 연 순서대로. */
    public synchronized List<MonitorItem> opened() {
        return List.copyOf(opened);
    }
}
