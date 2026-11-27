package tanager.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.WorkspaceLoadNotice;
import tanager.domain.history.ShellEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** state.json v1 → v2 마이그레이션(SDD §9.4 · §13.2, #178): JSON 트리 → JSON 트리 순수 함수. */
class MigrationV1ToV2Test {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final MigrationV1ToV2 MIGRATION = new MigrationV1ToV2();

    private static ObjectNode fixture(String name) {
        try (InputStream in = MigrationV1ToV2Test.class.getResourceAsStream("/state/" + name)) {
            return (ObjectNode) JSON.readTree(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode tree(String json) {
        return (ObjectNode) JSON.readTree(json);
    }

    private static JsonNode lastCwd(ShellEnvironment env, String path) {
        ObjectNode node = JSON.createObjectNode();
        switch (env) {
            case ShellEnvironment.Wsl wsl ->
                node.putObject("env").put("kind", "wsl").put("distro", wsl.distro());
            case ShellEnvironment.Windows _ -> node.putObject("env").put("kind", "windows");
        }
        return node.put("path", path);
    }

    @Test
    @DisplayName("v1 → v2 이다")
    void versions() {
        // then
        assertThat(MIGRATION.from()).isEqualTo(1);
        assertThat(MIGRATION.to()).isEqualTo(2);
    }

    @Test
    @DisplayName("이력만 있는 v0.4 파일은 schemaVersion 만 2 가 되고 history 는 그대로다")
    void historyOnlyKeepsHistory() {
        // given
        ObjectNode v1 = fixture("v1-history-only.json");

        // when
        Migration.Step step = MIGRATION.apply(v1);

        // then
        assertThat(step.root().get("schemaVersion").longValue()).isEqualTo(2);
        assertThat(step.root().get("history")).isEqualTo(v1.get("history"));
        assertThat(step.root().propertyNames()).containsExactlyInAnyOrder("schemaVersion", "history");
        assertThat(step.notices()).isEmpty();
    }

    @Test
    @DisplayName("SDD 9.2 예시: 탭 title 은 name 이 되고 lastCwd 문자열은 프로파일로 정한 환경의 {env, path} 가 된다")
    void sddExampleBecomesV2Shape() {
        // given
        ObjectNode v1 = fixture("v1-sdd-example.json");

        // when
        Migration.Step step = MIGRATION.apply(v1);

        // then
        JsonNode workspace = step.root().get("workspace");
        JsonNode tab = workspace.get("tabs").get(0);
        assertThat(tab.get("name").stringValue()).isEqualTo("dev");
        assertThat(tab.has("title")).isFalse();
        assertThat(tab.get("layout"))
                .isEqualTo(v1.get("workspace").get("tabs").get(0).get("layout"));
        JsonNode panes = workspace.get("panes");
        ShellEnvironment ubuntu = new ShellEnvironment.Wsl("Ubuntu-24.04");
        assertThat(panes.get("p1").get("lastCwd")).isEqualTo(lastCwd(ubuntu, "/home/me/proj/api"));
        assertThat(panes.get("p2").get("lastCwd")).isEqualTo(lastCwd(ubuntu, "/mnt/c/Users/user/Desktop/Tanager"));
        assertThat(panes.get("p3").get("lastCwd"))
                .isEqualTo(lastCwd(new ShellEnvironment.Windows(), "C:\\work\\infra"));
        assertThat(panes.get("p3").get("profile").stringValue()).isEqualTo("powershell");
        assertThat(step.root().get("history")).isEqualTo(v1.get("history"));
        assertThat(step.root().get("window")).isEqualTo(v1.get("window"));
        assertThat(step.notices()).isEmpty();
    }

    @Test
    @DisplayName("입력 트리를 바꾸지 않는다")
    void inputIsUntouched() {
        // given
        ObjectNode v1 = fixture("v1-sdd-example.json");
        ObjectNode copy = v1.deepCopy();

        // when
        MIGRATION.apply(v1);

        // then
        assertThat(v1).isEqualTo(copy);
    }

    @Test
    @DisplayName("cmd 프로파일은 Windows 환경이고, lastCwd 가 null 이거나 없는 창은 그대로 둔다")
    void cmdAndMissingCwd() {
        // given
        ObjectNode v1 = tree("""
                { "schemaVersion": 1, "history": { "entries": [] },
                  "workspace": { "activeTab": 0, "tabs": [],
                    "panes": { "p1": { "profile": "cmd", "lastCwd": "D:\\\\x" },
                               "p2": { "profile": "wsl:Debian", "lastCwd": null },
                               "p3": { "profile": "no-such" } } } }
                """);

        // when
        Migration.Step step = MIGRATION.apply(v1);

        // then
        JsonNode panes = step.root().get("workspace").get("panes");
        assertThat(panes.get("p1").get("lastCwd")).isEqualTo(lastCwd(new ShellEnvironment.Windows(), "D:\\x"));
        assertThat(panes.get("p2").get("lastCwd").isNull()).isTrue();
        assertThat(panes.get("p3").has("lastCwd")).isFalse();
        assertThat(step.notices()).isEmpty();
    }

    @Test
    @DisplayName("lastCwd 문자열의 환경을 프로파일로 알 수 없으면 workspace 만 버리고 알린다")
    void unknownProfileDiscardsWorkspace() {
        // given
        ObjectNode v1 = tree("""
                { "schemaVersion": 1, "history": { "entries": [] },
                  "workspace": { "activeTab": 0, "tabs": [],
                    "panes": { "p1": { "profile": "pwsh7", "lastCwd": "C:\\\\x" } } },
                  "window": { "maximized": true } }
                """);

        // when
        Migration.Step step = MIGRATION.apply(v1);

        // then
        assertThat(step.root().has("workspace")).isFalse();
        assertThat(step.root().get("window")).isEqualTo(v1.get("window"));
        assertThat(step.notices())
                .singleElement()
                .isInstanceOfSatisfying(
                        WorkspaceLoadNotice.PartDiscarded.class,
                        notice -> assertThat(notice.part()).isEqualTo("workspace"));
    }

    @Test
    @DisplayName("이미 name 이 있는 탭의 title 은 버리고, 모양이 다른 workspace 는 v2 읽기가 판정하도록 그대로 둔다")
    void leavesOtherShapesToReader() {
        // given
        ObjectNode named = tree("""
                { "schemaVersion": 1, "workspace": { "tabs": [ { "id": "t1", "name": "a", "title": "b" }, 7 ] } }
                """);
        ObjectNode array = tree("{ \"schemaVersion\": 1, \"workspace\": [] }");

        // when
        Migration.Step namedStep = MIGRATION.apply(named);
        Migration.Step arrayStep = MIGRATION.apply(array);

        // then
        JsonNode tab = namedStep.root().get("workspace").get("tabs").get(0);
        assertThat(tab.get("name").stringValue()).isEqualTo("a");
        assertThat(tab.has("title")).isFalse();
        assertThat(arrayStep.root().get("workspace")).isEqualTo(array.get("workspace"));
        assertThat(arrayStep.notices()).isEmpty();
    }

    @Test
    @DisplayName("체인: v1 은 현재 버전까지 올리고 Migrated(1, 2) 를 먼저 알린다. 현재 버전은 그대로다")
    void chainUpgradesToCurrent() {
        // given
        ObjectNode v1 = fixture("v1-sdd-example.json");
        ObjectNode v2 = fixture("v2.json");

        // when
        Migration.Step upgraded = Migrations.upgrade(v1, 1);
        Migration.Step current = Migrations.upgrade(v2, 2);

        // then
        assertThat(Migrations.CURRENT).isEqualTo(2);
        assertThat(upgraded.root().get("schemaVersion").longValue()).isEqualTo(Migrations.CURRENT);
        assertThat(upgraded.notices()).containsExactly(new WorkspaceLoadNotice.Migrated(1, 2));
        assertThat(current.root()).isEqualTo(v2);
        assertThat(current.notices()).isEmpty();
    }
}
