package tanager.domain.history;

import java.util.Objects;

/** 가져오기의 단위 — (출처, 환경). 배포판마다 따로 읽으므로 가져온 시각(워터마크)도 이 단위로 둔다. */
public record ImportOrigin(VisitSource source, ShellEnvironment env) {

    public ImportOrigin {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(env, "env");
    }
}
