package tanager.adapter.persistence;

import java.util.Optional;
import tanager.domain.history.ShellEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** state.json 의 환경 표기 {@code {"kind":"wsl","distro":"U"} | {"kind":"windows"}} — 이력 항목과 창의 lastCwd 가 함께 쓴다. */
final class Environments {
    private static final String KIND = "kind";
    private static final String DISTRO = "distro";
    private static final String KIND_WSL = "wsl";
    private static final String KIND_WINDOWS = "windows";
    private static final String WSL_PROFILE_PREFIX = "wsl:";

    private Environments() {}

    /** 모르는 kind · distro 없는 wsl · 객체가 아님은 비어 있다. */
    static Optional<ShellEnvironment> read(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            return Optional.empty();
        }
        Optional<String> kind = JsonValues.string(object.get(KIND));
        if (kind.equals(Optional.of(KIND_WINDOWS))) {
            return Optional.of(new ShellEnvironment.Windows());
        }
        if (kind.equals(Optional.of(KIND_WSL))) {
            return JsonValues.nonBlank(object.get(DISTRO)).map(ShellEnvironment.Wsl::new);
        }
        return Optional.empty();
    }

    /** {@code target} 에 kind(· distro)를 쓴다. */
    static ObjectNode write(ObjectNode target, ShellEnvironment env) {
        return switch (env) {
            case ShellEnvironment.Wsl wsl -> target.put(KIND, KIND_WSL).put(DISTRO, wsl.distro());
            case ShellEnvironment.Windows _ -> target.put(KIND, KIND_WINDOWS);
        };
    }

    /** 프로파일 ID(SDD §10.1)가 가리키는 환경: {@code wsl:<배포판>} · {@code powershell} · {@code cmd}. 그 밖은 비어 있다. */
    static Optional<ShellEnvironment> ofProfile(String profile) {
        if (profile.startsWith(WSL_PROFILE_PREFIX)) {
            String distro = profile.substring(WSL_PROFILE_PREFIX.length());
            return distro.isBlank() ? Optional.empty() : Optional.of(new ShellEnvironment.Wsl(distro));
        }
        if (profile.equals("powershell") || profile.equals("cmd")) {
            return Optional.of(new ShellEnvironment.Windows());
        }
        return Optional.empty();
    }

    /** 파일 순서용 키: windows → wsl:<배포판>. */
    static String orderKey(ShellEnvironment env) {
        return switch (env) {
            case ShellEnvironment.Windows _ -> KIND_WINDOWS;
            case ShellEnvironment.Wsl wsl -> KIND_WSL + ":" + wsl.distro();
        };
    }
}
