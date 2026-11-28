package tanager.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanager.application.port.out.DirectUiDispatcher;
import tanager.application.port.out.FakeHostFiles;
import tanager.application.port.out.FakePlatformModule;
import tanager.application.port.out.ScriptedPtyLauncher;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.TreeNode;
import tanager.domain.history.VisitSource;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;

/**
 * 조립이 Claude 대화 기록(실제 {@code ClaudeProjectsImporter})을 셸 탐지 뒤 읽어 이력에 넣고, 1초 안에 state.json 에 출처만 저장한다 — 요약 ·
 * 대화는 저장하지 않는다(FR-TK-09 · NFR-06, SDD §7.3, #215).
 */
class AssemblyConversationsTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellProfile CMD = new ShellProfile(
            new ProfileId("cmd"),
            "cmd",
            ShellKind.CMD,
            WIN,
            new LaunchTemplate("cmd.exe", List.of()),
            IntegrationMode.NONE);
    private static final String SESSION_ID = "0d9c2a4e-1111-4000-8000-00000000abcd";
    private static final String SUMMARY = "SECRET-SUMMARY 비밀 요약 문장";

    @TempDir
    Path appData;

    @TempDir
    Path home;

    private final List<Assembly> assemblies = new ArrayList<>();

    @AfterEach
    void shutdownAssemblies() {
        assemblies.forEach(Assembly::shutdown);
    }

    private static WorkspacePath win(String raw) {
        return WorkspacePath.of(WIN, raw).value().orElseThrow();
    }

    private void transcript() throws Exception {
        Path folder = Files.createDirectories(
                home.resolve(".claude").resolve("projects").resolve("C--work-talk"));
        Files.writeString(
                folder.resolve(SESSION_ID + ".jsonl"),
                """
                {"type":"mode","mode":"normal","sessionId":"%s"}
                {"type":"user","cwd":"C:\\\\work\\\\talk","sessionId":"%s","message":{"role":"user","content":"%s"}}
                """.formatted(SESSION_ID, SESSION_ID, SUMMARY),
                StandardCharsets.UTF_8);
    }

    private Assembly assemble() {
        WorkspacePath userHome = win("C:\\Users\\me");
        FakeHostFiles hostFiles = new FakeHostFiles().withHome(WIN, userHome).withHostPath(userHome, Optional.of(home));
        FakePlatformModule platform =
                new FakePlatformModule(List.of(CMD)).withAppData(appData).withHostFiles(hostFiles);
        Assembly assembly = Assembly.assemble(platform, new ScriptedPtyLauncher(), new DirectUiDispatcher());
        assemblies.add(assembly);
        return assembly;
    }

    @Test
    @DisplayName("셸 탐지 뒤 대화를 가져와 1초쯤 뒤 state.json 의 그 항목 sources 에 claude-code 가 있고 요약 · 대화 ID 는 파일 어디에도 없다")
    void savesSourceButNotConversation() throws Exception {
        // given
        transcript();
        Assembly assembly = assemble();

        // when
        assembly.startStore();
        assembly.startSettings();

        // then
        Path stateFile = appData.resolve("state.json");
        await().atMost(Duration.ofSeconds(10))
                .until(() -> Files.exists(stateFile)
                        && Files.readString(stateFile, StandardCharsets.UTF_8).contains("claude-code"));
        String saved = Files.readString(stateFile, StandardCharsets.UTF_8);
        assertThat(saved).contains("talk").doesNotContain("SECRET-SUMMARY", "비밀", SESSION_ID);
        assertThat(assembly.state()
                        .snapshot()
                        .history()
                        .find(win("C:\\work\\talk"))
                        .orElseThrow()
                        .sources())
                .contains(VisitSource.CLAUDE_CODE);
    }

    @Test
    @DisplayName("가져온 대화는 트리의 그 폴더 아래 대화 노드로 보인다")
    void conversationShowsInTree() throws Exception {
        // given
        transcript();
        Assembly assembly = assemble();

        // when
        assembly.startStore();
        assembly.startSettings();

        // then
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !assembly.state().snapshot().conversations().isEmpty());
        TreeNode.Folder folder = (TreeNode.Folder) assembly.historyTree()
                .tree("", false)
                .environments()
                .getFirst()
                .children()
                .getFirst();
        assertThat(folder.children())
                .filteredOn(TreeNode.Conversation.class::isInstance)
                .hasSize(1);
    }
}
