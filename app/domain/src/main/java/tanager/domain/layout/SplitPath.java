package tanager.domain.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 루트에서 Split 하나까지 내려가는 길(first / second 의 열). 경계선 드래그가 "어느 Split 의 비율"인지 가리킨다 — 창 ID 로는 Split 을
 * 가리킬 수 없어서다. 빈 경로는 루트다.
 */
public record SplitPath(List<Branch> branches) {
    /** 루트 Split. */
    public static final SplitPath ROOT = new SplitPath(List.of());

    /** 한 칸 내려갈 쪽. */
    public enum Branch {
        FIRST,
        SECOND
    }

    public SplitPath {
        branches = List.copyOf(branches);
    }

    public static SplitPath of(Branch... branches) {
        return new SplitPath(List.of(branches));
    }

    /** 이 경로에서 {@code branch} 쪽으로 한 칸 더 내려간 경로. */
    public SplitPath then(Branch branch) {
        Objects.requireNonNull(branch, "branch");
        List<Branch> next = new ArrayList<>(branches);
        next.add(branch);
        return new SplitPath(next);
    }
}
