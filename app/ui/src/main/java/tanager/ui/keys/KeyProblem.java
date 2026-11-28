package tanager.ui.keys;

import java.util.List;
import java.util.Objects;

/** 키 설정을 반영하며 생긴 문제(FR-CFG-01, #183). 상태줄 알림 한 줄이 된다({@link KeyProblemText}). */
public sealed interface KeyProblem {

    /** 키 원문을 읽지 못했다 — 그 동작은 기본 키를 쓴다. */
    record Unparseable(String action, String raw) implements KeyProblem {
        public Unparseable {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(raw, "raw");
        }
    }

    /** 모르는 동작 id 다 — 무시한다. */
    record UnknownAction(String action) implements KeyProblem {
        public UnknownAction {
            Objects.requireNonNull(action, "action");
        }
    }

    /** 셸 · 에디터 · 터미널이 쓰는 키다 — 그 동작은 기본 키를 쓴다. */
    record Reserved(String action, String key) implements KeyProblem {
        public Reserved {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(key, "key");
        }
    }

    /** 재정의끼리 같은 키다 — 모두 기본 키를 쓴다. {@code actions} 는 id 순서다. */
    record Conflict(String key, List<String> actions) implements KeyProblem {
        public Conflict {
            Objects.requireNonNull(key, "key");
            actions = List.copyOf(actions);
        }
    }

    /** 재정의({@code by})가 기본 키를 가져가 {@code action} 이 그 키를 잃었다. */
    record Unbound(String action, String key, String by) implements KeyProblem {
        public Unbound {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(by, "by");
        }
    }

    /** Ctrl+Alt 조합이라 AltGr(Windows 는 Ctrl+Alt 로 온다)로 치는 글자와 겹칠 수 있다 — 경고만 하고 적용한다. */
    record AltGraph(String action, String key) implements KeyProblem {
        public AltGraph {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(key, "key");
        }
    }

    /** {@code keys.prefix} 원문을 읽지 못해 prefix 모드(FR-LAY-08)를 껐다. */
    record PrefixUnparseable(String raw) implements KeyProblem {
        public PrefixUnparseable {
            Objects.requireNonNull(raw, "raw");
        }
    }

    /** prefix 키가 셸 · 에디터 · 터미널이 쓰는 키라 prefix 모드를 껐다(재정의와 같은 예약 키 규칙). */
    record PrefixReserved(String key) implements KeyProblem {
        public PrefixReserved {
            Objects.requireNonNull(key, "key");
        }
    }

    /** prefix 키를 동작 {@code action}(기본 키 또는 재정의)이 이미 써서 prefix 모드를 껐다. 그 동작의 키는 그대로다. */
    record PrefixConflict(String key, String action) implements KeyProblem {
        public PrefixConflict {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(action, "action");
        }
    }
}
