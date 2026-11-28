package tanager.application.port.in;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.ConfigError;
import tanager.application.port.out.ConfigWarning;
import tanager.application.port.out.HistoryLoadNotice;
import tanager.application.port.out.HistoryStoreError;
import tanager.application.port.out.WorkspaceLoadNotice;
import tanager.domain.history.ImportOrigin;
import tanager.domain.history.PastConversation;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.LiveSessions;
import tanager.domain.shared.PaneId;
import tanager.domain.workspace.WindowState;
import tanager.domain.workspace.Workspace;

/** 명령이 상태를 바꾼 뒤 구독자에게 알리는 이벤트. 세부 변경 대신 바뀐 값을 싣는다(ADR-0012). */
public sealed interface AppEvent {

    /** 작업 경로 이력이 바뀌었다. 새 이력 전체를 싣는다. */
    record HistoryChanged(WorkHistory history) implements AppEvent {
        public HistoryChanged {
            Objects.requireNonNull(history, "history");
        }
    }

    /** 더 이상 없다고 확인된 이력 경로가 바뀌었다(FR-HIS-08). 새 집합 전체를 싣는다 — 사이드바가 그 폴더를 흐리게 그린다. */
    record MissingPathsChanged(Set<WorkspacePath> missing) implements AppEvent {
        public MissingPathsChanged {
            missing = Set.copyOf(missing);
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

        /** 작업 공간을 읽으면서 생긴 일(부분 손상 · 마이그레이션, #179). 문서 단위의 일은 {@link Loaded} 로 온다. */
        record WorkspaceLoaded(WorkspaceLoadNotice notice) implements StoreProblem {
            public WorkspaceLoaded {
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

    /**
     * 가져온 이전 대화 목록이 바뀌었다(FR-TK-09, #215). origin 별 새 목록 전체를 싣는다 — 사이드바가 다시 투영한다. 저장하지 않는다.
     */
    record ConversationsChanged(Map<ImportOrigin, List<PastConversation>> conversations) implements AppEvent {
        public ConversationsChanged {
            Map<ImportOrigin, List<PastConversation>> copy = new HashMap<>();
            for (var e : conversations.entrySet()) {
                copy.put(e.getKey(), List.copyOf(e.getValue()));
            }
            conversations = Map.copyOf(copy);
        }
    }

    /** 대화 가져오기에 사용자에게 알릴 일이 생겼다 — 그 origin 은 이번 실행 동안 가져오지 않는다. 상태줄 알림 한 줄. */
    record ImportNotice(ImportProblem problem) implements AppEvent {
        public ImportNotice {
            Objects.requireNonNull(problem, "problem");
        }
    }

    /** 대화 가져오기 알림의 내용. 화면 문장은 UI 가 만든다(#137). */
    sealed interface ImportProblem {
        /** 가져오지 못한 (출처, 환경). */
        ImportOrigin origin();

        /** 개발자용 설명(로그). 경로 · 내용은 넣지 않는다. */
        String describe();

        /** 기록 파일은 있는데 형식을 알아보지 못했다(ADR-0020). */
        record UnknownFormat(ImportOrigin origin) implements ImportProblem {
            public UnknownFormat {
                Objects.requireNonNull(origin, "origin");
            }

            @Override
            public String describe() {
                return origin.source().id() + " 대화 기록 형식을 알아보지 못했다: " + origin.env();
            }
        }

        /** 기록 폴더 · 파일을 읽을 수 없다. */
        record Unreadable(ImportOrigin origin) implements ImportProblem {
            public Unreadable {
                Objects.requireNonNull(origin, "origin");
            }

            @Override
            public String describe() {
                return origin.source().id() + " 대화 기록을 읽을 수 없다: " + origin.env();
            }
        }
    }

    /**
     * 창 {@code pane} 의 세션이 뜬 뒤에 알릴 일이 생겼다(예: 시작 명령을 건너뜀, #216). 띄울 때 정해지는 알림은 {@link StartedSession#notices()}
     * 로 온다. 상태줄 알림 한 줄.
     */
    record SessionNotice(PaneId pane, StartNotice notice) implements AppEvent {
        public SessionNotice {
            Objects.requireNonNull(pane, "pane");
            Objects.requireNonNull(notice, "notice");
        }
    }

    /** 창 배치 · 포커스가 바뀌었다(#88). UI 는 이 값대로 창을 만들고 지운다. */
    record LayoutChanged(Workspace workspace) implements AppEvent {
        public LayoutChanged {
            Objects.requireNonNull(workspace, "workspace");
        }
    }

    /** 메인 창의 위치 · 크기 · 최대화 · 패널 상태가 바뀌었다(FR-RES-03, #180). 새 창 상태 전체를 싣는다. */
    record WindowChanged(WindowState window) implements AppEvent {
        public WindowChanged {
            Objects.requireNonNull(window, "window");
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
