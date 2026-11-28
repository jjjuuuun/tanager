package tanager.domain.toolkit;

import java.util.Objects;
import java.util.OptionalInt;
import tanager.domain.history.ShellEnvironment;

/**
 * 도구함을 읽다 생긴 문제. 화면 문장은 UI 가 만든다(#137). 파서 메시지는 원문(비밀값 포함 가능) 조각이 들어 있을 수 있어 담지 않는다.
 */
public sealed interface ToolkitWarning {

    /** 파일을 읽지 못했다(권한 · 입출력). */
    record Unreadable(SourceRef source) implements ToolkitWarning {
        public Unreadable {
            Objects.requireNonNull(source, "source");
        }
    }

    /** 파일 형식이 틀렸다. 줄 번호(1부터)만 담는다. */
    record Malformed(SourceRef source, OptionalInt line) implements ToolkitWarning {
        public Malformed {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(line, "line");
            if (line.isPresent() && line.getAsInt() < 1) {
                throw new IllegalArgumentException("줄 번호는 1 이상이다: " + line.getAsInt());
            }
        }
    }

    /** 공급자 전체가 실패했다. */
    record ProviderFailed(ProviderId provider) implements ToolkitWarning {
        public ProviderFailed {
            Objects.requireNonNull(provider, "provider");
        }
    }

    /** 그 환경의 홈을 몰라 사용자 범위를 읽지 못했다(FR-TK-04). */
    record HomeUnknown(ShellEnvironment env) implements ToolkitWarning {
        public HomeUnknown {
            Objects.requireNonNull(env, "env");
        }
    }
}
