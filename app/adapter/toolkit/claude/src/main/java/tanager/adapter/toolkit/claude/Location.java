package tanager.adapter.toolkit.claude;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;

/** 같은 파일 · 폴더의 두 이름 — 그 환경의 경로({@code env}, 항목 원본으로 보인다)와 앱이 읽을 경로({@code host}). */
record Location(WorkspacePath env, Path host) {
    Location {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(host, "host");
    }

    /** 아래 이름 하나. 그 환경의 경로로 만들 수 없는 이름(큰따옴표 등)이면 empty. */
    Optional<Location> child(String name) {
        String separator = switch (env.env()) {
            case ShellEnvironment.Windows _ -> "\\";
            case ShellEnvironment.Wsl _ -> "/";
        };
        String base = env.path();
        String raw = base.endsWith(separator) ? base + name : base + separator + name;
        return WorkspacePath.of(env.env(), raw).value().map(child -> new Location(child, host.resolve(name)));
    }

    /** 마지막 이름(앱이 읽는 쪽). */
    String name() {
        Path last = host.getFileName();
        return last == null ? host.toString() : last.toString();
    }
}
