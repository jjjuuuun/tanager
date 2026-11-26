package dev.tanager.application.service;

import dev.tanager.application.port.in.RecordVisitUseCase;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.CwdReport;
import dev.tanager.domain.session.CwdReports;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.OscScanner;
import dev.tanager.domain.shared.PaneId;
import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 세션 하나의 출력 구독자. OSC 7 · OSC 9;9 보고를 뽑아 그 창의 방문으로 알린다(ADR-0015). 중복 판정은 하지 않는다 — 그 창이 마지막으로 알린
 * 경로와 비교하는 일은 상태 쪽({@code LiveSessions.cwdReported})이 한다.
 *
 * <p>스캐너 값 · 고정한 host 는 이 세션의 읽기 스레드에서만 바꾼다(스레드 한정, java-style 8 의 예외). 콜백은 예외를 밖으로 던지지 않는다
 * — 던지면 스트림이 구독을 해제해 추적이 조용히 멈춘다.
 */
final class CwdTracker implements Consumer<byte[]> {
    private static final System.Logger LOG = System.getLogger(CwdTracker.class.getName());

    private final PaneId pane;
    private final ShellEnvironment env;
    private final IntegrationMode mode;
    private final RecordVisitUseCase visits;

    private OscScanner scanner = OscScanner.initial();
    // 세션의 첫 OSC 7 host. ssh 로 들어간 원격 셸의 보고가 로컬 이력을 더럽히지 않게 고정한다
    private Optional<String> host = Optional.empty();
    private boolean failureLogged;

    CwdTracker(PaneId pane, ShellEnvironment env, IntegrationMode mode, RecordVisitUseCase visits) {
        this.pane = Objects.requireNonNull(pane, "pane");
        this.env = Objects.requireNonNull(env, "env");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.visits = Objects.requireNonNull(visits, "visits");
    }

    @Override
    public void accept(byte[] chunk) {
        if (mode == IntegrationMode.NONE) {
            return;
        }
        OscScanner.Scan scan;
        try {
            scan = scanner.feed(chunk);
        } catch (RuntimeException e) {
            failed(e);
            return;
        }
        scanner = scan.next();
        // 보고가 없는 조각이 대부분이다 — 출력마다 이터레이터를 만들지 않는다
        if (scan.reports().isEmpty()) {
            return;
        }
        for (CwdReport report : scan.reports()) {
            try {
                handle(report);
            } catch (RuntimeException e) {
                failed(e);
            }
        }
    }

    private void handle(CwdReport report) {
        if (!accepts(report)) {
            return;
        }
        CwdReports.toPath(report, env).value().ifPresent(path -> {
            if (report instanceof CwdReport.FileUri uri && host.isEmpty()) {
                host = Optional.of(uri.host());
            }
            visits.record(pane, path);
        });
    }

    private boolean accepts(CwdReport report) {
        return switch (report) {
            case CwdReport.FileUri uri ->
                mode == IntegrationMode.OSC7 && host.map(uri.host()::equals).orElse(true);
            case CwdReport.WindowsPath _ -> mode == IntegrationMode.OSC9_9;
        };
    }

    // 출력 내용은 남기지 않는다. 같은 원인이 프롬프트마다 반복돼 로그를 채우지 않게 WARNING 은 한 번만, 이후는 DEBUG 로 남긴다
    private void failed(RuntimeException e) {
        if (failureLogged) {
            LOG.log(Level.DEBUG, "창 " + pane.value() + " 의 cwd 추적 중 예외(반복)", e);
            return;
        }
        failureLogged = true;
        LOG.log(Level.WARNING, "창 " + pane.value() + " 의 cwd 추적 중 예외 — 건너뛰고 계속 추적한다(이후 같은 예외는 DEBUG 로 남긴다)", e);
    }
}
