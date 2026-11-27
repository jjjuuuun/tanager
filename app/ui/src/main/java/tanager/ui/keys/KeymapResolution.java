package tanager.ui.keys;

import java.util.List;
import java.util.Objects;

/** 기본 키에 재정의를 얹은 결과: 쓸 키 표와 알릴 문제(#183). */
public record KeymapResolution(Keymap keymap, List<KeyProblem> problems) {
    public KeymapResolution {
        Objects.requireNonNull(keymap, "keymap");
        problems = List.copyOf(problems);
    }
}
