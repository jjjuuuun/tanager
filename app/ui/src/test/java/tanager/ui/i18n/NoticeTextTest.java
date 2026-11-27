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
        assertNoHangul(EN.of(new StartNotice.StartDirMissing(tanager.domain.history.WorkspacePath.of(
                        new tanager.domain.history.ShellEnvironment.Windows(), "C:\\gone")
                .value()
                .orElseThrow())));
        assertNoHangul(EN.of(
                new PlatformSelectionError.NoMatch(new OperatingSystem("Plan9", "4", "mips"), List.of("windows"))));
    }

    @Test
    @DisplayName("열기 실패: 지운 배포판 · Windows 셸 없음 · 바꿀 수 없는 경로 · 모르는 셸(#91)")
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
    }
}
