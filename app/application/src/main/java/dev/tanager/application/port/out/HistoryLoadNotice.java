package dev.tanager.application.port.out;

import java.util.Objects;
import java.util.Optional;

/** 이력을 읽으면서 생겨 사용자에게 알릴 일. 읽기는 계속된다. */
public sealed interface HistoryLoadNotice {

    /** 사용자에게 보여 줄 한 줄 설명. */
    String describe();

    /**
     * 저장 파일이 손상돼 백업(.bak)에서 읽었다. 손상 원본은 {@code corruptMovedTo}(파일 이름)에 복사해 두고 저장 파일을 백업 내용으로 바꿨다
     * — 다른 인스턴스가 쓰고 있어 아무것도 바꾸지 않았으면 empty.
     */
    record RecoveredFromBackup(Optional<String> corruptMovedTo) implements HistoryLoadNotice {
        public RecoveredFromBackup {
            Objects.requireNonNull(corruptMovedTo, "corruptMovedTo");
        }

        @Override
        public String describe() {
            return corruptMovedTo
                    .map(name -> "이력 파일이 손상돼 백업에서 복구했다(손상 파일은 " + name + " 로 옮겼다)")
                    .orElse("이력 파일이 손상돼 백업에서 복구했다");
        }
    }

    /** 저장 파일과 백업이 모두 손상돼 원본을 {@code movedTo}(파일 이름)로 옮기고 빈 이력으로 시작했다. */
    record CorruptMovedAside(String movedTo) implements HistoryLoadNotice {
        public CorruptMovedAside {
            Objects.requireNonNull(movedTo, "movedTo");
        }

        @Override
        public String describe() {
            return "이력 파일이 손상돼 " + movedTo + " 로 옮기고 빈 이력으로 시작했다";
        }
    }

    /** 읽을 수 없는 항목 {@code count} 개를 건너뛰었다. */
    record EntriesSkipped(int count) implements HistoryLoadNotice {
        public EntriesSkipped {
            if (count < 1) {
                throw new IllegalArgumentException("count 는 1 이상이어야 한다: " + count);
            }
        }

        @Override
        public String describe() {
            return "이력 항목 " + count + " 개를 읽지 못해 건너뛰었다";
        }
    }

    /**
     * 이 앱보다 새 버전({@code version})이 쓴 파일이다. 읽을 수 있는 만큼 읽고 저장하지 않는다. 버전은 int 범위를 넘을 수 있어 long 이다(long 도
     * 넘으면 {@link Long#MAX_VALUE}).
     */
    record NewerSchema(long version) implements HistoryLoadNotice {
        @Override
        public String describe() {
            return "새 버전(schemaVersion " + version + ")이 쓴 이력 파일이라 읽기만 한다";
        }
    }

    /** 잠금 파일(state.lock)을 열 수 없어 다른 인스턴스와 겹치지 않는지 알 수 없다. 읽기만 한다. */
    record LockUnavailable(String detail) implements HistoryLoadNotice {
        public LockUnavailable {
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String describe() {
            return "이력 잠금 파일을 열 수 없어 읽기만 한다: " + detail;
        }
    }

    /** 다른 Tanager 인스턴스가 저장 파일을 쓰고 있다. 읽기만 한다. */
    record LockedByOtherInstance() implements HistoryLoadNotice {
        @Override
        public String describe() {
            return "다른 Tanager 가 이력 파일을 쓰고 있어 읽기만 한다";
        }
    }
}
