package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.RecordingVisits;
import tanager.application.port.in.RecordingVisits.Visit;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.shared.PaneId;

class CwdTrackerTest {
    private static final ShellEnvironment UBUNTU = new ShellEnvironment.Wsl("Ubuntu");
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final PaneId P1 = new PaneId("p1");

    private final RecordingVisits visits = new RecordingVisits();

    private static byte[] osc7(String host, String path) {
        return ("\u001b]7;file://" + host + path + "\u0007").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] osc99(String path) {
        return ("\u001b]9;9;\"" + path + "\"\u001b\\").getBytes(StandardCharsets.UTF_8);
    }

    private static WorkspacePath wsl(String raw) {
        return WorkspacePath.of(UBUNTU, raw).value().orElseThrow();
    }

    private static WorkspacePath win(String raw) {
        return WorkspacePath.of(WIN, raw).value().orElseThrow();
    }

    private CwdTracker bash() {
        return new CwdTracker(P1, UBUNTU, IntegrationMode.OSC7, visits);
    }

    private CwdTracker powershell() {
        return new CwdTracker(P1, WIN, IntegrationMode.OSC9_9, visits);
    }

    @Test
    @DisplayName("WSL 셸의 OSC 7 보고를 그 창의 방문으로 알린다")
    void osc7ReportBecomesVisit() {
        // when
        bash().accept(osc7("PC", "/home/me"));

        // then
        assertThat(visits.visits()).containsExactly(new Visit(P1, wsl("/home/me")));
    }

    @Test
    @DisplayName("PowerShell 의 OSC 9;9 보고를 Windows 경로 방문으로 알린다")
    void osc99ReportBecomesVisit() {
        // when
        powershell().accept(osc99("C:\\work"));

        // then
        assertThat(visits.visits()).containsExactly(new Visit(P1, win("C:\\work")));
    }

    @Test
    @DisplayName("조각으로 나뉘어 온 OSC 도 이어 붙여 한 번 알린다")
    void sequenceSplitAcrossChunksIsReassembled() {
        // given
        byte[] whole = osc7("PC", "/home/me");
        CwdTracker tracker = bash();

        // when
        tracker.accept(java.util.Arrays.copyOfRange(whole, 0, 7));
        assertThat(visits.visits()).isEmpty();
        tracker.accept(java.util.Arrays.copyOfRange(whole, 7, whole.length));

        // then
        assertThat(visits.visits()).containsExactly(new Visit(P1, wsl("/home/me")));
    }

    @Test
    @DisplayName("OSC 가 없는 출력은 아무것도 알리지 않는다")
    void plainOutputReportsNothing() {
        // when
        bash().accept("ls -la\r\ntotal 0\r\n".getBytes(StandardCharsets.UTF_8));

        // then
        assertThat(visits.visits()).isEmpty();
    }

    @Test
    @DisplayName("첫 OSC 7 의 host 로 고정하고 다른 host 의 OSC 7 은 무시한다")
    void osc7FromOtherHostIsIgnored() {
        // given
        CwdTracker tracker = bash();
        tracker.accept(osc7("PC", "/home/me"));

        // when
        tracker.accept(osc7("remote", "/srv/app"));
        tracker.accept(osc7("PC", "/home/me/work"));

        // then
        assertThat(visits.visits())
                .containsExactly(new Visit(P1, wsl("/home/me")), new Visit(P1, wsl("/home/me/work")));
    }

    @Test
    @DisplayName("PowerShell(OSC9_9) 세션에 온 OSC 7 은 무시한다")
    void osc7IsIgnoredInOsc99Mode() {
        // when
        powershell().accept(osc7("PC", "/home/me"));

        // then
        assertThat(visits.visits()).isEmpty();
    }

    @Test
    @DisplayName("WSL(OSC7) 세션에 온 OSC 9;9 는 무시한다")
    void osc99IsIgnoredInOsc7Mode() {
        // when
        bash().accept(osc99("C:\\work"));

        // then
        assertThat(visits.visits()).isEmpty();
    }

    @Test
    @DisplayName("통합 모드가 NONE 이면 아무것도 알리지 않는다")
    void noneModeReportsNothing() {
        // given
        CwdTracker tracker = new CwdTracker(P1, UBUNTU, IntegrationMode.NONE, visits);

        // when
        tracker.accept(osc7("PC", "/home/me"));

        // then
        assertThat(visits.visits()).isEmpty();
    }

    @Test
    @DisplayName("경로로 해석할 수 없는 보고는 건너뛰고 다음 보고를 계속 받는다")
    void unparsableReportIsSkipped() {
        // given
        CwdTracker tracker = powershell();

        // when
        tracker.accept(osc99("not-a-windows-path"));
        tracker.accept(osc99("C:\\work"));

        // then
        assertThat(visits.visits()).containsExactly(new Visit(P1, win("C:\\work")));
    }

    @Test
    @DisplayName("기록 중 예외가 나도 밖으로 던지지 않고 다음 조각을 계속 추적한다")
    void exceptionInsideDoesNotStopTracking() {
        // given
        CwdTracker tracker = bash();
        visits.failNextWith(new IllegalStateException("boom"));

        // when
        assertThatCode(() -> tracker.accept(osc7("PC", "/a"))).doesNotThrowAnyException();
        assertThatCode(() -> tracker.accept(osc7("PC", "/b"))).doesNotThrowAnyException();

        // then
        assertThat(visits.visits()).containsExactly(new Visit(P1, wsl("/a")), new Visit(P1, wsl("/b")));
    }

    @Test
    @DisplayName("예외가 나도 호스트 고정과 스캐너 상태는 유지된다")
    void stateSurvivesException() {
        // given
        CwdTracker tracker = bash();
        tracker.accept(osc7("PC", "/a"));
        visits.failNextWith(new IllegalStateException("boom"));
        tracker.accept(osc7("PC", "/b"));

        // when
        tracker.accept(osc7("remote", "/c"));
        byte[] whole = osc7("PC", "/d");
        tracker.accept(java.util.Arrays.copyOfRange(whole, 0, 9));
        tracker.accept(java.util.Arrays.copyOfRange(whole, 9, whole.length));

        // then
        assertThat(visits.visits()).extracting(Visit::cwd).containsExactly(wsl("/a"), wsl("/b"), wsl("/d"));
    }

    @Test
    @DisplayName("경로 거부로 버린 보고는 오류 종류만 DEBUG 로 남기고 경로 문자열은 남기지 않는다")
    void rejectedReportIsLoggedWithoutPath() {
        // given 큰따옴표는 OSC 7 에서 %22 로 온다(#107). 다른 환경의 보고는 NotAbsolute 로 거부된다
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(CwdTracker.class.getName());
        Level previous = logger.getLevel();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try {
            // when
            bash().accept(osc7("PC", "/tmp/secret%22dir"));
            powershell().accept(osc99("relative-secret"));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }

        // then
        assertThat(visits.visits()).isEmpty();
        assertThat(records).hasSize(2).allSatisfy(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.FINE);
            assertThat(record.getMessage()).doesNotContain("secret");
            assertThat(record.getThrown()).isNull();
        });
        assertThat(records.get(0).getMessage()).contains("UnsafeCharacter");
        assertThat(records.get(1).getMessage()).contains("NotAbsolute");
    }
}
