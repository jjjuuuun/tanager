package tanager.adapter.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import tanager.application.port.out.WorkspaceLoadNotice;
import tanager.domain.history.ShellEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * v1 → v2(#178). history 는 그대로다. SDD §9.2 v1 예시 모양의 workspace 를 바꾼다: 탭의 {@code title} → {@code name}, 창의 문자열
 * {@code lastCwd} → 프로파일 ID 로 정한 환경의 {@code {"env":…, "path":…}}. 환경을 정할 수 없으면 workspace 만 버리고 알린다. 그 밖의 모양은
 * 그대로 두어 v2 읽기가 판정한다.
 */
final class MigrationV1ToV2 implements Migration {
    private static final String WORKSPACE = "workspace";
    private static final String LAST_CWD = "lastCwd";

    @Override
    public long from() {
        return 1;
    }

    @Override
    public Step apply(ObjectNode root) {
        ObjectNode out = root.deepCopy();
        out.put("schemaVersion", to());
        if (!(out.get(WORKSPACE) instanceof ObjectNode workspace)) {
            return new Step(out, List.of());
        }
        if (workspace.get("tabs") instanceof ArrayNode tabs) {
            for (JsonNode tab : tabs) {
                if (tab instanceof ObjectNode t && t.has("title")) {
                    JsonNode title = t.remove("title");
                    if (!t.has("name")) {
                        t.set("name", title);
                    }
                }
            }
        }
        if (workspace.get("panes") instanceof ObjectNode panes) {
            for (Map.Entry<String, JsonNode> entry : panes.properties()) {
                if (!(entry.getValue() instanceof ObjectNode pane)
                        || JsonValues.string(pane.get(LAST_CWD)).isEmpty()) {
                    continue;
                }
                Optional<ShellEnvironment> env =
                        JsonValues.string(pane.get("profile")).flatMap(Environments::ofProfile);
                if (env.isEmpty()) {
                    out.remove(WORKSPACE);
                    return new Step(
                            out,
                            List.of(new WorkspaceLoadNotice.PartDiscarded(
                                    WORKSPACE, "창 " + entry.getKey() + " 의 lastCwd 환경을 프로파일로 알 수 없다")));
                }
                String path = pane.get(LAST_CWD).stringValue();
                ObjectNode cwd = pane.putObject(LAST_CWD);
                Environments.write(cwd.putObject("env"), env.get());
                cwd.put("path", path);
            }
        }
        return new Step(out, List.of());
    }
}
