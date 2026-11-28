package tanager.ui.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.platform.OperatingSystem;
import tanager.application.platform.PlatformSelectionError;
import tanager.application.port.in.AppEvent;
import tanager.application.port.in.OpenError;
import tanager.application.port.in.StartNotice;
import tanager.application.port.out.ConfigError;
import tanager.application.port.out.ConfigWarning;
import tanager.application.port.out.HistoryLoadNotice;
import tanager.application.port.out.HistoryStoreError;
import tanager.application.port.out.Integration;
import tanager.application.port.out.LaunchError;
import tanager.domain.history.AliasProblem;
import tanager.domain.history.ImportOrigin;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.session.ProfileId;

/** 하위 계층의 구조화 값 → 화면 문장(#137). 영어 화면에 한국어가 섞이지 않고, 한국어 화면은 전과 같다. */
class NoticeTextTest {
    private static final NoticeText EN = new NoticeText(Messages.of(Locale.US));
    private static final NoticeText KO = new NoticeText(Messages.of(Locale.KOREA));

    private static void assertNoHangul(String text) {
        assertThat(text).doesNotContainPattern("[\\uAC00-\\uD7A3]");
    }

    @Test
    @DisplayName("영어 화면: 시작 알림 · 저장 실패 · 설정 경고 · 기동 실패 · 별칭 오류가 영어다")
    void englishSentences() {
        assertThat(EN.of(new StartNotice.IntegrationUnavailable(
                        new Integration.Problem.Crashed("IllegalStateException"))))
                .isEqualTo("Shell integration is unavailable, so directory changes are not recorded"
                        + " — Shell integration install failed with IllegalStateException");
        assertThat(EN.of(new AppEvent.StoreProblem.SaveFailed(
                        new HistoryStoreError.ReadOnly(HistoryStoreError.ReadOnlyReason.OTHER_INSTANCE))))
                .isEqualTo("History was not saved: another Tanager is using it");
        assertThat(EN.of(new ConfigWarning.IgnoredValue(
                        "defaultProfile", ConfigWarning.IgnoredReason.NOT_NON_EMPTY_STRING)))
                .isEqualTo("Ignored setting defaultProfile: not a non-empty string");
        assertThat(EN.of(new LaunchError.ExecutableNotFound("C:\\x\\pwsh.exe")))
                .isEqualTo("Executable not found: C:\\x\\pwsh.exe");
        assertThat(EN.of(AliasProblem.TOO_LONG)).isEqualTo("An alias can have at most 64 characters");
    }

    @Test
    @DisplayName("셸 통합 설치 실패: 알려진 파일 문제는 번역하고, 모르는 문제는 OS 의 이유를 그대로 보인다")
    void installFailure() {
        var denied = new Integration.Problem.InstallFailed("C:\\a", Integration.FileProblem.ACCESS_DENIED, "");
        var other = new Integration.Problem.InstallFailed("C:\\a", Integration.FileProblem.OTHER, "디스크 꽉 참");
        assertThat(EN.of(denied)).isEqualTo("Could not install the shell integration scripts: C:\\a (access denied)");
        assertThat(KO.of(denied)).isEqualTo("셸 통합 스크립트를 설치하지 못했다: C:\\a (접근이 거부됐다)");
        assertThat(EN.of(other)).endsWith("(디스크 꽉 참)");
    }

    @Test
    @DisplayName("한국어 화면의 문장은 #137 전(describe)과 같다")
    void koreanMatchesPreviousSentences() {
        var skipped = new HistoryLoadNotice.EntriesSkipped(3);
        var recovered = new HistoryLoadNotice.RecoveredFromBackup(Optional.of("state.json.corrupt"));
        var newer = new HistoryLoadNotice.NewerSchema(9);
        assertThat(KO.of(new AppEvent.StoreProblem.Loaded(skipped))).isEqualTo(skipped.describe());
        assertThat(KO.of(new AppEvent.StoreProblem.Loaded(recovered))).isEqualTo(recovered.describe());
        assertThat(KO.of(new AppEvent.StoreProblem.Loaded(newer))).isEqualTo(newer.describe());
        var io = new HistoryStoreError.Io("disk");
        assertThat(KO.of(new AppEvent.StoreProblem.SaveFailed(io))).isEqualTo(io.describe());
        var unreadable = new StartNotice.ConfigUnreadable(new ConfigError.Unreadable("권한"));
        assertThat(KO.of(unreadable)).isEqualTo(unreadable.describe());
        var notSaved = new StartNotice.DefaultNotSaved(new ProfileId("cmd"), new ConfigError.WriteFailed("disk"));
        assertThat(KO.of(notSaved)).isEqualTo(notSaved.describe());
        var launch = new LaunchError.StartFailed("winpty");
        assertThat(KO.of(launch)).isEqualTo(launch.describe());
    }

    @Test
    @DisplayName("모든 경우가 비어 있지 않은 문장이 된다(영어에는 한글이 없다)")
    void everyCaseHasText() {
        for (HistoryStoreError.ReadOnlyReason reason : HistoryStoreError.ReadOnlyReason.values()) {
            String text = EN.of(new AppEvent.StoreProblem.SaveFailed(new HistoryStoreError.ReadOnly(reason)));
            assertNoHangul(text);
            assertThat(text).doesNotEndWith(": ");
        }
        for (ConfigWarning.IgnoredReason reason : ConfigWarning.IgnoredReason.values()) {
            assertNoHangul(EN.of(new ConfigWarning.IgnoredValue("k", reason)));
        }
        for (ConfigError.Risk risk : ConfigError.Risk.values()) {
            assertNoHangul(EN.of(new ConfigError.WouldOverwrite(risk, "schemaVersion 7")));
        }
        for (AliasProblem problem : AliasProblem.values()) {
            assertNoHangul(EN.of(problem));
            assertThat(KO.of(problem)).isNotBlank();
        }
        for (Integration.FileProblem cause : Integration.FileProblem.values()) {
            assertNoHangul(EN.of(new Integration.Problem.InstallFailed("p", cause, "os reason")));
        }
        List<HistoryLoadNotice> loads = List.of(
                new HistoryLoadNotice.RecoveredFromBackup(Optional.empty()),
                new HistoryLoadNotice.CorruptMovedAside("state.json.1"),
                new HistoryLoadNotice.LockUnavailable("io"),
                new HistoryLoadNotice.LockedByOtherInstance());
        for (HistoryLoadNotice notice : loads) {
            assertNoHangul(EN.of(new AppEvent.StoreProblem.Loaded(notice)));
        }
        assertNoHangul(EN.of(new StartNotice.MissingDirectory(tanager.domain.history.WorkspacePath.of(
                        new tanager.domain.history.ShellEnvironment.Windows(), "C:\\gone")
                .value()
                .orElseThrow())));
        assertNoHangul(EN.of(
                new PlatformSelectionError.NoMatch(new OperatingSystem("Plan9", "4", "mips"), List.of("windows"))));
    }

    @Test
    @DisplayName("설정 다시 읽기(#182): 범위 밖 값 · 이전 설정 유지 알림이 두 언어로 나온다")
    void settingsReloadNotices() {
        var clamped = new ConfigWarning.OutOfRange("terminal.size", "200", "32");
        var kept = new AppEvent.SettingsProblem.KeptPrevious(new ConfigError.Corrupt("(3:5)"));
        assertThat(EN.of(clamped)).isEqualTo("Setting terminal.size = 200 is out of range — using 32");
        assertThat(KO.of(clamped)).isEqualTo(clamped.describe());
        assertThat(KO.of(kept)).isEqualTo("설정 파일을 읽지 못해 이전 설정을 쓴다: 설정 파일이 손상됐다: (3:5)");
        assertNoHangul(EN.of(kept));
        assertThat(EN.of(new AppEvent.SettingsProblem.Warned(clamped))).isEqualTo(EN.of(clamped));
    }

    @Test
    @DisplayName("열기 실패: 지운 배포판 · Windows 셸 없음 · 바꿀 수 없는 경로 · 모르는 셸(#91) · 세션 없는 창(#92)")
    void openErrors() {
        var home = tanager.domain.history.WorkspacePath.of(
                        new tanager.domain.history.ShellEnvironment.Wsl("U"), "/home/me")
                .value()
                .orElseThrow();
        assertThat(KO.of(new OpenError.NoProfileForEnvironment(new tanager.domain.history.ShellEnvironment.Wsl("Z"))))
                .isEqualTo("배포판 Z 를 찾을 수 없다");
        assertThat(EN.of(new OpenError.NoProfileForEnvironment(new tanager.domain.history.ShellEnvironment.Wsl("Z"))))
                .isEqualTo("WSL distribution Z was not found");
        assertNoHangul(
                EN.of(new OpenError.NoProfileForEnvironment(new tanager.domain.history.ShellEnvironment.Windows())));
        assertThat(EN.of(new OpenError.EnvironmentMismatch(new ProfileId("powershell"), home)))
                .isEqualTo("/home/me cannot be opened with powershell");
        assertNoHangul(EN.of(new OpenError.UnknownProfile(new ProfileId("gone"))));
        assertThat(KO.of(new OpenError.UnknownPane(new tanager.domain.shared.PaneId("p9"))))
                .isEqualTo("창 p9 에 세션이 없다");
        assertThat(EN.of(new OpenError.UnknownPane(new tanager.domain.shared.PaneId("p9"))))
                .isEqualTo("Pane p9 has no session");
    }

    @Test
    @DisplayName("복원(#179): 사라진 경로 알림과 작업 공간 불러오기 알림이 두 언어로 나온다")
    void restoreNotices() {
        // given
        var gone = tanager.domain.history.WorkspacePath.of(
                        new tanager.domain.history.ShellEnvironment.Wsl("Ubuntu"), "/home/me/gone")
                .value()
                .orElseThrow();
        var window = new AppEvent.StoreProblem.WorkspaceLoaded(
                new tanager.application.port.out.WorkspaceLoadNotice.PartDiscarded("window", "좌표 일부만"));
        var workspace = new AppEvent.StoreProblem.WorkspaceLoaded(
                new tanager.application.port.out.WorkspaceLoadNotice.PartDiscarded("workspace", "탭 없음"));
        var dropped = new AppEvent.StoreProblem.WorkspaceLoaded(
                new tanager.application.port.out.WorkspaceLoadNotice.PanesDropped(2));
        var migrated = new AppEvent.StoreProblem.WorkspaceLoaded(
                new tanager.application.port.out.WorkspaceLoadNotice.Migrated(1, 2));

        // when / then
        assertThat(KO.of(new StartNotice.MissingDirectory(gone))).isEqualTo("/home/me/gone 이 없어 홈에서 열었다");
        assertThat(EN.of(new StartNotice.MissingDirectory(gone)))
                .isEqualTo("/home/me/gone does not exist — opened in the home directory");
        assertThat(KO.of(window)).isEqualTo("저장한 창 위치 · 크기를 읽지 못해 기본값을 썼다");
        assertThat(KO.of(workspace)).isEqualTo("저장한 탭 · 창 배치를 읽지 못해 창 하나로 시작했다");
        assertThat(KO.of(dropped)).isEqualTo("저장한 창 2 개를 읽지 못해 빼고 복원했다");
        assertThat(EN.of(migrated)).isEqualTo("Read the state file from an older format (schemaVersion 1)");
        for (var problem : List.of(window, workspace, dropped, migrated)) {
            assertNoHangul(EN.of(problem));
        }
    }

    @Test
    @DisplayName("대화 가져오기 알림: 도구 이름 · 환경을 넣어 영어 · 한국어로 쓴다(#215)")
    void importProblems() {
        // given
        var winOrigin = new ImportOrigin(VisitSource.CLAUDE_CODE, new ShellEnvironment.Windows());
        var ubuntuOrigin = new ImportOrigin(VisitSource.CLAUDE_CODE, new ShellEnvironment.Wsl("Ubuntu"));
        var unknown = new AppEvent.ImportProblem.UnknownFormat(winOrigin);
        var unreadable = new AppEvent.ImportProblem.Unreadable(ubuntuOrigin);

        // when / then
        assertThat(EN.of(unknown))
                .isEqualTo("Could not recognize the Claude Code conversation history format in Windows"
                        + " — not importing it until restart");
        assertThat(KO.of(unknown)).isEqualTo("Windows 의 Claude Code 대화 기록 형식을 알아보지 못해 이번 실행 동안 가져오지 않는다");
        assertThat(EN.of(unreadable))
                .isEqualTo("Could not read the Claude Code conversation history in WSL · Ubuntu"
                        + " — not importing it until restart");
        assertThat(KO.of(unreadable)).isEqualTo("WSL · Ubuntu 의 Claude Code 대화 기록을 읽지 못해 이번 실행 동안 가져오지 않는다");
        assertThat(EN.of(new AppEvent.ImportProblem.Unreadable(
                        new ImportOrigin(new VisitSource("codex"), new ShellEnvironment.Windows()))))
                .contains("codex");
    }

    @Test
    @DisplayName("시작 명령을 건너뛴 이유 세 가지와 공급자 없는 출처가 두 언어로 나온다(#216)")
    void startupCommandSkipped() {
        // given
        var line = new tanager.domain.session.ShellLine("claude --resume 7f3a");
        var mismatch =
                new StartNotice.StartupCommandSkipped(line, StartNotice.StartupCommandSkipped.Reason.PATH_MISMATCH);
        var exited = new StartNotice.StartupCommandSkipped(line, StartNotice.StartupCommandSkipped.Reason.SHELL_EXITED);
        var timedOut = new StartNotice.StartupCommandSkipped(line, StartNotice.StartupCommandSkipped.Reason.TIMED_OUT);
        var unknown = new OpenError.UnknownSource(new VisitSource("codex"));

        // when / then
        assertThat(EN.of(mismatch))
                .isEqualTo("Did not run \"claude --resume 7f3a\" — the shell did not start in the requested folder");
        assertThat(KO.of(mismatch)).isEqualTo("셸이 요청한 폴더에서 뜨지 않아 \"claude --resume 7f3a\" 를 실행하지 않았다");
        assertThat(EN.of(exited))
                .isEqualTo("Did not run \"claude --resume 7f3a\" — the shell exited before its first prompt");
        assertThat(KO.of(exited)).isEqualTo("셸이 첫 프롬프트 전에 끝나 \"claude --resume 7f3a\" 를 실행하지 않았다");
        assertThat(EN.of(timedOut))
                .isEqualTo("Did not run \"claude --resume 7f3a\" — the shell did not report its folder in time");
        assertThat(KO.of(timedOut)).isEqualTo("셸이 제때 경로를 알리지 않아 \"claude --resume 7f3a\" 를 실행하지 않았다");
        assertThat(EN.of(unknown)).isEqualTo("No conversation provider for codex");
        assertThat(KO.of(unknown)).isEqualTo("codex 대화 공급자가 없다");
        assertThat(EN.of(new OpenError.UnknownSource(VisitSource.CLAUDE_CODE)))
                .isEqualTo("No conversation provider for Claude Code");
    }
}
