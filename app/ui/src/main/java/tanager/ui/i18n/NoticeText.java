package tanager.ui.i18n;

import java.util.Objects;
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
import tanager.domain.history.Alias;
import tanager.domain.history.AliasProblem;
import tanager.domain.history.ShellEnvironment;

/**
 * 하위 계층이 넘긴 구조화 값을 화면 문장으로 바꾼다(#137, ADR-0017). 하위 계층의 {@code describe()} 는 로그용이라 화면에 쓰지 않는다. 모든
 * switch 는 default 없이 전 경우를 다뤄, 새 경우가 생기면 컴파일러가 번역 누락을 잡는다.
 */
public final class NoticeText {
    /** 앱의 화면 언어. */
    public static final NoticeText DEFAULT = new NoticeText(Messages.DEFAULT);

    private final Messages messages;

    public NoticeText(Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    private String t(Msg msg, Object... args) {
        return messages.text(msg, args);
    }

    /** 세션 시작 알림. */
    public String of(StartNotice notice) {
        return switch (notice) {
            case StartNotice.ConfigUnreadable(ConfigError error) -> t(Msg.START_CONFIG_UNREADABLE, of(error));
            case StartNotice.ConfigWarned(ConfigWarning warning) -> of(warning);
            case StartNotice.StartDirInvalid(String raw) -> t(Msg.START_DIR_INVALID, raw);
            case StartNotice.StartDirMissing(var requested) -> t(Msg.START_DIR_MISSING, requested.path());
            case StartNotice.DefaultNotSaved(var profile, ConfigError error) ->
                t(Msg.START_DEFAULT_NOT_SAVED, profile.value(), of(error));
            case StartNotice.IntegrationUnavailable(Integration.Problem problem) ->
                t(Msg.START_INTEGRATION_UNAVAILABLE, of(problem));
        };
    }

    /** 셸 통합을 쓸 수 없는 이유. */
    public String of(Integration.Problem problem) {
        return switch (problem) {
            case Integration.Problem.NotSupported() -> t(Msg.INTEGRATION_NOT_SUPPORTED);
            case Integration.Problem.InstallFailed(String path, Integration.FileProblem cause, String detail) ->
                t(Msg.INTEGRATION_INSTALL_FAILED, path, cause == Integration.FileProblem.OTHER ? detail : of(cause));
            case Integration.Problem.Crashed(String exception) -> t(Msg.INTEGRATION_CRASHED, exception);
        };
    }

    // OTHER 는 OS 의 이유를 그대로 보인다(호출 쪽)
    private String of(Integration.FileProblem problem) {
        return switch (problem) {
            case ACCESS_DENIED -> t(Msg.FILE_ACCESS_DENIED);
            case FILE_EXISTS -> t(Msg.FILE_EXISTS);
            case DIRECTORY_EXISTS -> t(Msg.FILE_DIRECTORY_EXISTS);
            case NO_SUCH_PATH -> t(Msg.FILE_NO_SUCH_PATH);
            case NOT_DIRECTORY -> t(Msg.FILE_NOT_DIRECTORY);
            case OTHER -> "";
        };
    }

    /** 설정 파일을 읽거나 쓰지 못한 이유. */
    public String of(ConfigError error) {
        return switch (error) {
            case ConfigError.Unreadable(String detail) -> t(Msg.CONFIG_UNREADABLE, detail);
            case ConfigError.Corrupt(String detail) -> t(Msg.CONFIG_CORRUPT, detail);
            case ConfigError.WouldOverwrite(ConfigError.Risk risk, String detail) ->
                switch (risk) {
                    case CORRUPT_FILE -> t(Msg.CONFIG_KEEP_CORRUPT, detail);
                    case NEWER_VERSION -> t(Msg.CONFIG_KEEP_NEWER, detail);
                    case UNREADABLE_VERSION -> t(Msg.CONFIG_KEEP_UNREADABLE_VERSION, detail);
                };
            case ConfigError.WriteFailed(String detail) -> t(Msg.CONFIG_WRITE_FAILED, detail);
        };
    }

    /** 설정을 읽다 난 경고. */
    public String of(ConfigWarning warning) {
        return switch (warning) {
            case ConfigWarning.IgnoredValue(String key, ConfigWarning.IgnoredReason reason) ->
                t(Msg.CONFIG_IGNORED, key, of(reason));
            case ConfigWarning.NewerVersion(int found) -> t(Msg.CONFIG_NEWER_VERSION, found);
            case ConfigWarning.OutOfRange(String key, String found, String used) ->
                t(Msg.CONFIG_OUT_OF_RANGE, key, found, used);
        };
    }

    private String of(ConfigWarning.IgnoredReason reason) {
        return switch (reason) {
            case NOT_POSITIVE_INTEGER -> t(Msg.CONFIG_IGNORED_NOT_POSITIVE_INTEGER);
            case NOT_OBJECT -> t(Msg.CONFIG_IGNORED_NOT_OBJECT);
            case EMPTY_PROFILE_ID -> t(Msg.CONFIG_IGNORED_EMPTY_PROFILE_ID);
            case NOT_NON_EMPTY_STRING -> t(Msg.CONFIG_IGNORED_NOT_NON_EMPTY_STRING);
            case UNKNOWN_VALUE -> t(Msg.CONFIG_IGNORED_UNKNOWN_VALUE);
            case NOT_SUPPORTED_YET -> t(Msg.CONFIG_IGNORED_NOT_SUPPORTED_YET);
            case NOT_INTEGER -> t(Msg.CONFIG_IGNORED_NOT_INTEGER);
            case NOT_NUMBER -> t(Msg.CONFIG_IGNORED_NOT_NUMBER);
            case NOT_BOOLEAN -> t(Msg.CONFIG_IGNORED_NOT_BOOLEAN);
            case NOT_ARRAY -> t(Msg.CONFIG_IGNORED_NOT_ARRAY);
            case NOT_STRING -> t(Msg.CONFIG_IGNORED_NOT_STRING);
            case EMPTY_NAME -> t(Msg.CONFIG_IGNORED_EMPTY_NAME);
        };
    }

    /** 설정을 다시 읽다 생긴 알림(FR-CFG-03). */
    public String of(AppEvent.SettingsProblem problem) {
        return switch (problem) {
            case AppEvent.SettingsProblem.KeptPrevious(ConfigError error) -> t(Msg.SETTINGS_KEPT_PREVIOUS, of(error));
            case AppEvent.SettingsProblem.Warned(ConfigWarning warning) -> of(warning);
        };
    }

    /** 셸 프로세스를 띄우지 못한 이유. */
    public String of(LaunchError error) {
        return switch (error) {
            case LaunchError.ExecutableNotFound(String executable) -> t(Msg.LAUNCH_EXECUTABLE_NOT_FOUND, executable);
            case LaunchError.WorkingDirectoryNotFound(String directory) -> t(Msg.LAUNCH_DIRECTORY_NOT_FOUND, directory);
            case LaunchError.StartFailed(String detail) -> t(Msg.LAUNCH_START_FAILED, detail);
        };
    }

    /** 이력 저장소 알림. */
    public String of(AppEvent.StoreProblem problem) {
        return switch (problem) {
            case AppEvent.StoreProblem.Loaded(HistoryLoadNotice notice) -> of(notice);
            case AppEvent.StoreProblem.LoadFailed(HistoryStoreError error) -> of(error);
            case AppEvent.StoreProblem.SaveFailed(HistoryStoreError error) -> of(error);
        };
    }

    private String of(HistoryLoadNotice notice) {
        return switch (notice) {
            case HistoryLoadNotice.RecoveredFromBackup(var kept) ->
                kept.map(name -> t(Msg.HISTORY_RECOVERED_KEPT, name)).orElseGet(() -> t(Msg.HISTORY_RECOVERED));
            case HistoryLoadNotice.CorruptMovedAside(String movedTo) -> t(Msg.HISTORY_CORRUPT_MOVED, movedTo);
            case HistoryLoadNotice.EntriesSkipped(int count) -> t(Msg.HISTORY_ENTRIES_SKIPPED, count);
            case HistoryLoadNotice.NewerSchema(long version) -> t(Msg.HISTORY_NEWER_SCHEMA, version);
            case HistoryLoadNotice.LockUnavailable(String detail) -> t(Msg.HISTORY_LOCK_UNAVAILABLE, detail);
            case HistoryLoadNotice.LockedByOtherInstance() -> t(Msg.HISTORY_LOCKED_BY_OTHER);
        };
    }

    private String of(HistoryStoreError error) {
        return switch (error) {
            case HistoryStoreError.Io(String detail) -> t(Msg.HISTORY_IO, detail);
            case HistoryStoreError.ReadOnly(HistoryStoreError.ReadOnlyReason reason) ->
                t(Msg.HISTORY_NOT_SAVED, of(reason));
        };
    }

    private String of(HistoryStoreError.ReadOnlyReason reason) {
        return switch (reason) {
            case NOT_LOADED -> t(Msg.HISTORY_READ_ONLY_NOT_LOADED);
            case LOAD_FAILED -> t(Msg.HISTORY_READ_ONLY_LOAD_FAILED);
            case CLOSED -> t(Msg.HISTORY_READ_ONLY_CLOSED);
            case NEWER_SCHEMA -> t(Msg.HISTORY_READ_ONLY_NEWER_SCHEMA);
            case OTHER_INSTANCE -> t(Msg.HISTORY_READ_ONLY_OTHER_INSTANCE);
            case LOCK_UNAVAILABLE -> t(Msg.HISTORY_READ_ONLY_LOCK_UNAVAILABLE);
        };
    }

    /** 별칭으로 쓸 수 없는 이유(입력칸 아래). */
    public String of(AliasProblem problem) {
        return switch (problem) {
            case BLANK -> t(Msg.ALIAS_BLANK);
            case TOO_LONG -> t(Msg.ALIAS_TOO_LONG, Alias.MAX_CODE_POINTS);
            case INVISIBLE_CHARACTER -> t(Msg.ALIAS_INVISIBLE);
            case NO_VISIBLE_CHARACTER -> t(Msg.ALIAS_NO_VISIBLE);
        };
    }

    /** 트리에서 창을 열지 못한 이유(#91). */
    public String of(OpenError error) {
        return switch (error) {
            case OpenError.NoProfileForEnvironment(ShellEnvironment env) ->
                switch (env) {
                    case ShellEnvironment.Wsl(String distro) -> t(Msg.OPEN_NO_WSL, distro);
                    case ShellEnvironment.Windows _ -> t(Msg.OPEN_NO_WINDOWS);
                };
            case OpenError.EnvironmentMismatch(var profile, var path) ->
                t(Msg.OPEN_MISMATCH, profile.value(), path.path());
            case OpenError.UnknownProfile(var profile) -> t(Msg.OPEN_UNKNOWN_PROFILE, profile.value());
            case OpenError.UnknownPane(var pane) -> t(Msg.OPEN_UNKNOWN_PANE, pane.value());
        };
    }

    /** 플랫폼을 고르지 못해 시작할 수 없는 이유(오류 대화 상자). */
    public String of(PlatformSelectionError error) {
        return switch (error) {
            case PlatformSelectionError.NoMatch(var os, var candidates) ->
                t(Msg.PLATFORM_NO_MATCH, os.describe(), candidates);
            case PlatformSelectionError.Ambiguous(var os, var matches) ->
                t(Msg.PLATFORM_AMBIGUOUS, os.describe(), String.join(", ", matches));
        };
    }
}
