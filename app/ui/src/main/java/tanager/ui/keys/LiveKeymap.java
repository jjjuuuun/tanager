package tanager.ui.keys;

import java.util.List;
import java.util.Objects;
import tanager.application.port.out.AppConfig;

/**
 * 지금 쓰는 키 표. 설정이 바뀌면({@code SettingsChanged}) 기본 키 위에 새 재정의를 얹어 바꾼다 — 다시 시작하지 않는다(#183). EDT 에서만
 * 쓴다.
 */
public final class LiveKeymap {
    private Keymap current = Keymap.defaults();

    /** 지금 쓰는 키 표. */
    public Keymap current() {
        return current;
    }

    /** {@code config} 의 키 설정으로 바꾸고 알릴 문제를 돌려준다. 앞의 재정의는 남지 않는다. */
    public List<KeyProblem> apply(AppConfig config) {
        Objects.requireNonNull(config, "config");
        KeymapResolution resolution = Keymap.resolve(Keymap.defaults(), config.keys());
        current = resolution.keymap();
        return resolution.problems();
    }
}
