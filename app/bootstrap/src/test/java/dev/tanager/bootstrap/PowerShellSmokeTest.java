package dev.tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jediterm.terminal.ui.JediTermWidget;
import dev.tanager.adapter.pty.Pty4jLauncher;
import dev.tanager.application.platform.PlatformSelection;
import dev.tanager.application.port.in.StartRequest;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.domain.session.SessionState;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.session.TerminalSession;
import dev.tanager.ui.font.TerminalFontSet;
import dev.tanager.ui.terminal.StreamTtyConnector;
import dev.tanager.ui.terminal.TanagerTermWidget;
import dev.tanager.ui.terminal.TerminalSettings;
import dev.tanager.ui.theme.JediTermColors;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * DA1 응답으로 ConPTY 의 3초 지연이 사라졌는지 실제 PowerShell 5.1 로 확인한다(ADR-0011).
 *
 * <p>실제 셸과 위젯이 연결되는지 보는 기능 테스트다. CI 러너의 PowerShell 콜드 스타트는 수 초가 걸려 제한(10초)을 넉넉히 둔다.
 * ConPTY DA1 3초 지연이 사라졌는지는 headless 단위 테스트({@code TanagerTermWidgetTest})가 결정적으로 본다.
 * 시작 성능 목표(NFR-02)를 재는 테스트도 아니다. 실측(개발 PC 약 0.35초)은 로그로 남긴다.
 */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
class PowerShellSmokeTest {
    private static final System.Logger LOG = System.getLogger(PowerShellSmokeTest.class.getName());
    private static final Duration LIMIT = Duration.ofSeconds(10);

    @Test
    @DisplayName("실제 PowerShell 이 위젯에 프롬프트를 그리고 exit 2 는 종료 코드 2 로 끝난다")
    void promptAppearsQuicklyAndExitCodeArrives() throws Exception {
        // given
        PlatformModule platform = currentPlatform();
        Assembly assembly = Assembly.assemble(platform, new Pty4jLauncher());
        // 조립이 미리 시작한 탐지 결과를 그대로 쓴다(탐지는 한 번)
        ShellProfile powershell = assembly.profiles().list().stream()
                .filter(p -> p.kind() == ShellKind.WINDOWS_POWERSHELL)
                .findFirst()
                .orElseThrow();
        StartedSession started = assembly.sessions()
                .start(new StartRequest(Optional.of(powershell.id()), Optional.empty(), new TerminalSize(120, 30)))
                .value()
                .orElseThrow();
        StreamTtyConnector connector = StreamTtyConnector.attach(started.stream(), "powershell");
        AtomicReference<LookAndFeel> previous = new AtomicReference<>();
        AtomicReference<JediTermWidget> widget = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                previous.set(UIManager.getLookAndFeel());
                setLookAndFeel("dev.tanager.ui.theme.SoftDarkLaf");
                widget.set(new TanagerTermWidget(
                        new TerminalSettings(TerminalFontSet.fallback(), JediTermColors.current())));
            });

            // when
            long connectedAt = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> {
                widget.get().setTtyConnector(connector);
                widget.get().start();
            });
            started.stream().start();

            // then
            await().atMost(LIMIT)
                    .untilAsserted(() -> assertThat(
                                    widget.get().getTerminalTextBuffer().getScreenLines())
                            .contains("PS "));
            long promptAt = System.nanoTime();
            connector.write("exit 2\r");
            TerminalSession exited =
                    started.exited().toCompletableFuture().get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
            long exitedAt = System.nanoTime();
            LOG.log(
                    System.Logger.Level.INFO,
                    "PowerShell 프롬프트까지 {0}ms, exit 뒤 종료까지 {1}ms",
                    (promptAt - connectedAt) / 1_000_000,
                    (exitedAt - promptAt) / 1_000_000);
            assertThat(exited.state()).isInstanceOf(SessionState.Exited.class);
            assertThat(((SessionState.Exited) exited.state()).exitCode()).isEqualTo(2);
        } finally {
            connector.close();
            SwingUtilities.invokeAndWait(() -> {
                if (widget.get() != null) {
                    widget.get().close();
                }
                if (previous.get() != null) {
                    setLookAndFeel(previous.get().getClass().getName());
                }
            });
        }
    }

    private static PlatformModule currentPlatform() {
        List<PlatformModule> candidates = ServiceLoader.load(PlatformModule.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        return PlatformSelection.select(candidates, TanagerApp.currentOs())
                .value()
                .orElseThrow();
    }

    private static void setLookAndFeel(String className) {
        try {
            UIManager.setLookAndFeel(className);
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
            throw new IllegalStateException(e);
        }
    }
}
