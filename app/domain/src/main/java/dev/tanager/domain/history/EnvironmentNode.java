package dev.tanager.domain.history;

import java.util.List;
import java.util.Objects;

/** 트리의 환경 노드("WSL · U", "Windows"). 자식은 cwd 를 모르는 세션(번호순) 다음 루트 폴더들(라벨순). */
public record EnvironmentNode(ShellEnvironment env, List<TreeNode> children) {
    public EnvironmentNode {
        Objects.requireNonNull(env, "env");
        children = List.copyOf(children);
    }
}
