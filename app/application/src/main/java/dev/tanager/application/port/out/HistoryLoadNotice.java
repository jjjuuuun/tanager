package dev.tanager.application.port.out;

import java.util.Objects;

/** 이력을 읽으면서 생겨 사용자에게 알릴 일. 읽기는 계속된다. */
public sealed interface HistoryLoadNotice {

    /** 사용자에게 보여 줄 한 줄 설명. */
    String describe();

    /** 저장 파일이 손상돼 백업(.bak)에서 읽었다. */
    record RecoveredFromBackup() implements HistoryLoadNotice {
        @Override
        public String describe() {
            return "이력 파일이 손상돼 백업에서 복구했다";
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

    /** 이 앱보다 새 버전({@code version})이 쓴 파일이다. 읽을 수 있는 만큼 읽고 저장하지 않는다. */
    record NewerSchema(int version) implements HistoryLoadNotice {
        @Override
        public String describe() {
            return "새 버전(schemaVersion " + version + ")이 쓴 이력 파일이라 읽기만 한다";
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
