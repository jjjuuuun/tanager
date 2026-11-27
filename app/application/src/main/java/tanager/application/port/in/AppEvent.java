package tanager.application.port.in;

import java.util.List;
import java.util.Objects;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigError;
import tanager.application.port.out.ConfigWarning;
import tanager.application.port.out.HistoryLoadNotice;
import tanager.application.port.out.HistoryStoreError;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.LiveSessions;
import tanager.domain.shared.PaneId;
import tanager.domain.workspace.Workspace;

/** 명령이 상태를 바꾼 뒤 구독자에게 알리는 이벤트. 세부 변경 대신 바뀐 값을 싣는다(ADR-0012). */
public sealed interface AppEvent {

    /** 작업 경로 이력이 바뀌었다. 새 이력 전체를 싣는다. */
    record HistoryChanged(WorkHistory history) implements AppEvent {
        public HistoryChanged {
            Objects.requireNonNull(history, "history");
        }
    }

    /** 창 {@code pane} 의 셸이 {@code cwd} 로 옮겼다(셸 통합이 알린 경로). */
    record SessionCwdChanged(PaneId pane, WorkspacePath cwd) implements AppEvent {
        public SessionCwdChanged {
            Objects.requireNonNull(pane, "pane");
            Objects.requireNonNull(cwd, "cwd");
        }
    }

    /** 창별 세션 목록이 바뀌었다(띄움 · 끝남). 새 목록 전체를 싣는다. */
    record SessionsChanged(LiveSessions sessions) implements AppEvent {
        public SessionsChanged {
            Objects.requireNonNull(sessions, "sessions");
        }
    }

    /** 이력 저장소에 사용자에게 알릴 일이 생겼다(불러오기 복구 · 읽기 전용 · 저장 실패 등). 상태줄 알림 한 줄. */
    record StoreNotice(StoreProblem problem) implements AppEvent {
        public StoreNotice {
            Objects.requireNonNull(problem, "problem");
        }
    }

    /** 이력 저장소 알림의 내용. 화면 문장은 UI 가 만든다(#137). */
    sealed interface StoreProblem {
        /** 개발자용 설명(로그). */
        String describe();

        /** 불러오면서 생긴 일(복구 · 건너뜀 · 읽기 전용). */
        record Loaded(HistoryLoadNotice notice) implements StoreProblem {
            public Loaded {
                Objects.requireNonNull(notice, "notice");
            }

            @Override
            public String describe() {
                return notice.describe();
            }
        }

        /** 불러오지 못했다 — 읽기 전용이 됐다. */
        record LoadFailed(HistoryStoreError error) implements StoreProblem {
            public LoadFailed {
                Objects.requireNonNull(error, "error");
            }

            @Override
            public String describe() {
                return error.describe();
            }
        }

        /** 저장하지 못했다. */
        record SaveFailed(HistoryStoreError error) implements StoreProblem {
            public SaveFailed {
                Objects.requireNonNull(error, "error");
            }

            @Override
            public String describe() {
                return error.describe();
            }
        }
    }

    /** 창 배치 · 포커스가 바뀌었다(#88). UI 는 이 값대로 창을 만들고 지운다. */
    record LayoutChanged(Workspace workspace) implements AppEvent {
        public LayoutChanged {
            Objects.requireNonNull(workspace, "workspace");
        }
    }

    /** 마지막 창을 닫았다 — 앱을 끝낸다(ADR-0011). 작업 공간은 그대로다. */
    record LastPaneClosed() implements AppEvent {}

    /** 셸 탐지가 끝나 환경 순서(트리의 환경 노드 순서)가 정해졌다. 탐지 순서대로 한 번씩. */
    record EnvironmentsChanged(List<ShellEnvironment> environments) implements AppEvent {
        public EnvironmentsChanged {
            environments = List.copyOf(environments);
        }
    }

    /**
     * 설정을 (다시) 읽어 값이 바뀌었다(FR-CFG-03). 시작할 때도 한 번 나간다 — UI 는 이 이벤트만 보고 설정을 적용한다. 같은 값이면 나가지
     * 않는다.
     */
    record SettingsChanged(AppConfig config) implements AppEvent {
        public SettingsChanged {
            Objects.requireNonNull(config, "config");
        }
    }

    /** 설정을 다시 읽다 사용자에게 알릴 일이 생겼다. 상태줄 알림 한 줄. */
    record SettingsNotice(SettingsProblem problem) implements AppEvent {
        public SettingsNotice {
            Objects.requireNonNull(problem, "problem");
        }
    }

    /** 설정 다시 읽기 알림의 내용. 화면 문장은 UI 가 만든다(#137). */
    sealed interface SettingsProblem {
        /** 개발자용 설명(로그). */
        String describe();

        /** 읽지 못해(손상 등) 마지막 정상 설정을 그대로 쓴다. */
        record KeptPrevious(ConfigError error) implements SettingsProblem {
            public KeptPrevious {
                Objects.requireNonNull(error, "error");
            }

            @Override
            public String describe() {
                return "이전 설정을 쓴다: " + error.describe();
            }
        }

        /** 읽었지만 일부 값을 쓰지 못했다(새 설정은 적용했다). */
        record Warned(ConfigWarning warning) implements SettingsProblem {
            public Warned {
                Objects.requireNonNull(warning, "warning");
            }

            @Override
            public String describe() {
                return warning.describe();
            }
        }
    }
}
