package dev.tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tanager.application.port.in.StartFailure;
import dev.tanager.application.port.in.StartNotice;
import dev.tanager.application.port.in.StartRequest;
import dev.tanager.application.port.in.StartedSession;
import dev.tanager.application.port.out.FakePlatformModule;
import dev.tanager.application.port.out.FakeShellIntegration;
import dev.tanager.application.port.out.ScriptedPtyLauncher;
import dev.tanager.application.port.out.TerminalSize;
import dev.tanager.domain.history.ShellEnvironment;
import dev.tanager.domain.session.IntegrationMode;
import dev.tanager.domain.session.LaunchTemplate;
import dev.tanager.domain.session.ProfileId;
import dev.tanager.domain.session.ProfileResolution;
import dev.tanager.domain.session.ShellKind;
import dev.tanager.domain.session.ShellProfile;
import dev.tanager.domain.shared.PaneId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssemblyTest {
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            new ShellEnvironment.Windows(),
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);

    @TempDir
    Path appData;

    private static final TerminalSize SIZE = new TerminalSize(80, 24);

    @Test
    @DisplayName("조립하면 셸 탐지가 미리 한 번 돌고, 기억한 셸이 사라졌으면 셸을 띄우지 않고 선택을 요구한다")
    void assemblePreDetectsShellsAndRequiresChoiceWhenRememberedShellIsGone() throws Exception {
        // given
        Files.writeString(appData.resolve("config.json"), "{ \"defaultProfile\": \"ghost\" }", StandardCharsets.UTF_8);
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).withAppData(appData);
        ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();

        // when
        Assembly assembly = Assembly.assemble(platform, launcher);

        // then 셸 탐지는 start 를 부르기 전에 미리 시작된다
        await().atMost(Duration.ofSeconds(2)).until(() -> platform.shellsCalls() == 1);

        // when
        var result = assembly.sessions().start(StartRequest.remembered(new PaneId("p1"), SIZE));

        // then
        assertThat(result.error())
                .contains(new StartFailure.ChoiceRequired(
                        new ProfileResolution.Choose(List.of(CMD), Optional.of(new ProfileId("ghost"))), List.of()));
        assertThat(launcher.commands()).isEmpty();
        assertThat(platform.shellsCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("기억을 켜고 고르면 config.json 에 기본 셸만 더하고 다음 시작은 선택 없이 그 셸이 뜬다")
    void rememberingWritesDefaultKeepingOtherKeysAndNextStartSkipsChoice() throws Exception {
        // given
        Path file = appData.resolve("config.json");
        Files.writeString(
                file,
                "{\"schemaVersion\":1,\"profiles\":{\"cmd\":{\"startDir\":\"C:\\\\work\"}},\"appearance\":{\"theme\":\"DARK\"}}",
                StandardCharsets.UTF_8);
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).withAppData(appData);
        ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();
        Assembly assembly = Assembly.assemble(platform, launcher);

        // when
        StartedSession first = assembly.sessions()
                .start(StartRequest.chosen(new PaneId("p1"), CMD.id(), true, SIZE))
                .value()
                .orElseThrow();
        first.stream().close();
        StartedSession second = assembly.sessions()
                .start(StartRequest.remembered(new PaneId("p1"), SIZE))
                .value()
                .orElseThrow();

        // then
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).containsPattern("\"defaultProfile\"\\s*:\\s*\"cmd\"");
        assertThat(text).containsPattern("\"theme\"\\s*:\\s*\"DARK\"");
        assertThat(text).containsPattern("\"startDir\"\\s*:\\s*\"C:\\\\\\\\work\"");
        assertThat(second.session().profile()).isEqualTo(CMD);
        second.stream().close();
    }

    @Test
    @DisplayName("손상된 config.json 이어도 셸은 뜨고 기본 셸 저장 실패를 알리며 파일은 그대로다")
    void corruptConfigIsNotOverwrittenWhenRemembering() throws Exception {
        // given
        Path file = appData.resolve("config.json");
        Files.writeString(file, "{ broken", StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).withAppData(appData);
        Assembly assembly = Assembly.assemble(platform, new ScriptedPtyLauncher());

        // when
        StartedSession started = assembly.sessions()
                .start(StartRequest.chosen(new PaneId("p1"), CMD.id(), true, SIZE))
                .value()
                .orElseThrow();

        // then
        assertThat(started.notices()).anyMatch(StartNotice.DefaultNotSaved.class::isInstance);
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        started.stream().close();
    }

    @Test
    @DisplayName("조립하면 셸 통합 설치가 백그라운드에서 한 번 돈다")
    void assembleInstallsShellIntegrationOnce() {
        // given
        FakeShellIntegration integration = FakeShellIntegration.installedAt(appData.resolve("shell-integration"));
        FakePlatformModule platform =
                new FakePlatformModule(List.of(CMD)).withAppData(appData).withIntegration(integration);

        // when
        Assembly.assemble(platform, new ScriptedPtyLauncher());

        // then
        await().atMost(Duration.ofSeconds(2)).until(() -> integration.installCalls() == 1);
        assertThat(integration.installCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("셸 통합 설치가 예외를 던져도 조립과 셸 시작은 된다")
    void throwingShellIntegrationDoesNotBreakAssemblyOrStart() {
        // given
        FakeShellIntegration integration = FakeShellIntegration.throwing(new IllegalStateException("설치 고장"));
        FakePlatformModule platform =
                new FakePlatformModule(List.of(CMD)).withAppData(appData).withIntegration(integration);
        ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();

        // when
        Assembly assembly = Assembly.assemble(platform, launcher);
        await().atMost(Duration.ofSeconds(2)).until(() -> integration.installCalls() == 1);
        var result = assembly.sessions().start(StartRequest.chosen(new PaneId("p1"), CMD.id(), false, SIZE));

        // then
        StartedSession started = result.value().orElseThrow();
        assertThat(started.session().profile()).isEqualTo(CMD);
        assertThat(launcher.commands()).hasSize(1);
        started.stream().close();
    }
}
