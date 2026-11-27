package tanager.application.port.out;

import java.util.Objects;

/** 작업 공간을 읽으면서 생겨 사용자에게 알릴 일(#178). 이력은 그대로 읽는다. */
public sealed interface WorkspaceLoadNotice {

    /** 사용자에게 보여 줄 한 줄 설명. */
    String describe();

    /** {@code part}("workspace" · "window")의 값이 틀려 그 부분만 버렸다. window 면 기본 창 상태를 쓴다. 파일은 옮기지 않는다. */
    record PartDiscarded(String part, String reason) implements WorkspaceLoadNotice {
        public PartDiscarded {
            Objects.requireNonNull(part, "part");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String describe() {
            return "저장한 " + part + " 를 읽지 못해 버렸다: " + reason;
        }
    }

    /** 명세 · 위치가 틀린 창 {@code count} 개를 트리에서 빼고(형제로 축약) 복원했다. */
    record PanesDropped(int count) implements WorkspaceLoadNotice {
        public PanesDropped {
            if (count < 1) {
                throw new IllegalArgumentException("count 는 1 이상이어야 한다: " + count);
            }
        }

        @Override
        public String describe() {
            return "저장한 창 " + count + " 개를 읽지 못해 빼고 복원했다";
        }
    }

    /** 이전 형식({@code from})의 파일을 {@code to} 형식으로 바꿔 읽었다. 저장하면 {@code to} 로 쓴다. */
    record Migrated(long from, long to) implements WorkspaceLoadNotice {
        public Migrated {
            if (from >= to) {
                throw new IllegalArgumentException("마이그레이션은 버전을 올린다: " + from + " → " + to);
            }
        }

        @Override
        public String describe() {
            return "상태 파일을 schemaVersion " + from + " 에서 " + to + " 형식으로 읽었다";
        }
    }
}
