package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.in.StartRequest;
import tanager.application.port.in.StartedSession;
import tanager.application.port.out.DirectUiDispatcher;
import tanager.application.port.out.FakePlatformModule;
import tanager.application.port.out.ScriptedPtyLauncher;
import tanager.application.port.out.ScriptedTerminalStream;
import tanager.application.port.out.TerminalSize;
import tanager.domain.history.ConversationId;
import tanager.domain.history.PastConversation;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.WorkingDir;
import tanager.domain.workspace.PaneSpec;

/**
 * 조립이 대화 이어 하기를 실제 Claude 공급자({@code ClaudeProjectsImporter})의 명령으로 잇는다. 시작 명령은 새 창의 셸에만 쓰이고
 * state.json 에는 남지 않는다(FR-TK-09, #216).
 */
class AssemblyResumeTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            WIN,
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);
    private static final String SESSION_ID = "0d9c2a4e-2222-4000-8000-00000000beef";

    @TempDir
    Path appData;

    private final List<Assembly> assemblies = new ArrayList<>();
    private final ScriptedPtyLauncher launcher = new ScriptedPtyLauncher();

    @AfterEach
    void shutdownAssemblies() {
        assemblies.forEach(Assembly::shutdown);
    }

    private Assembly assemble() {
        FakePlatformModule platform = new FakePlatformModule(List.of(CMD)).withAppData(appData);
        Assembly assembly = Assembly.assemble(platform, launcher, new DirectUiDispatcher());
        assemblies.add(assembly);
        assembly.startStore();
        assembly.startSettings();
        return assembly;
    }

    private static PastConversation conversation() {
        WorkspacePath cwd = WorkspacePath.of(WIN, "C:\\work\\talk").value().orElseThrow();
        return new PastConversation(
                VisitSource.CLAUDE_CODE, new ConversationId(SESSION_ID), cwd, Optional.empty(), Instant.EPOCH);
    }

    @Test
    @DisplayName("이어 하기로 연 cmd 창에 1.5초쯤 뒤 claude --resume <id> 와 Enter 가 한 번 쓰이고, 저장된 state.json 에는 창만 있고 명령은 없다")
    void resumeWritesClaudeCommandButStateHasNoTrace() throws Exception {
        // given
        Assembly assembly = assemble();

        // when
        PaneSpec spec = assembly.resumeConversation()
                .resume(conversation(), Optional.empty())
                .toCompletableFuture()
                .join()
                .value()
                .orElseThrow();
        StartedSession session = assembly.sessions()
                .start(new StartRequest(
                        spec.pane(),
                        spec.profile(),
                        false,
                        spec.dir().map(WorkingDir.At::new),
                        new TerminalSize(80, 24)))
                .value()
                .orElseThrow();
        session.stream().start();

        // then
        ScriptedTerminalStream stream = (ScriptedTerminalStream) session.stream();
        await().atMost(Duration.ofSeconds(10)).until(() -> !stream.written().isEmpty());
        assertThat(stream.written())
                .extracting(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .containsExactly("claude --resume " + SESSION_ID + "\r");
        Path stateFile = appData.resolve("state.json");
        await().atMost(Duration.ofSeconds(10))
                .until(() -> Files.exists(stateFile)
                        && Files.readString(stateFile, StandardCharsets.UTF_8).contains("\"p2\""));
        assertThat(Files.readString(stateFile, StandardCharsets.UTF_8))
                .contains("talk")
                .doesNotContain(SESSION_ID, "--resume", "claude --");
    }
}
