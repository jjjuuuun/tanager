package tanager.adapter.toolkit.claude;

import java.util.Optional;
import tanager.domain.toolkit.ToolkitKind;
import tanager.domain.toolkit.ToolkitScope;

/**
 * 훅 · 권한(Claude Code 문서 settings · hooks · permissions · managed-settings, 2026-11-28 확인). 관리형 {@code
 * managed-settings.json} 과 {@code managed-settings.d/*.json}(이름 순, 숨김 파일 제외) → 로컬 {@code .claude/settings.local.json} →
 * 프로젝트 {@code .claude/settings.json} → 사용자 {@code ~/.claude/settings.json} — https://code.claude.com/docs/en/settings#settings-precedence
 * · https://code.claude.com/docs/en/managed-settings. 훅 · 권한 규칙은 범위를 넘어 합쳐 적용되므로 가리지 않는다.
 */
final class SettingsReader implements ClaudeReader {

    static final String SETTINGS_FILE = "settings.json";
    static final String LOCAL_FILE = "settings.local.json";
    static final String MANAGED_FILE = "managed-settings.json";
    static final String MANAGED_DROP_INS = "managed-settings.d";

    @Override
    public void read(ClaudeLayout layout, Collector out) {
        layout.managed().ifPresent(dir -> {
            dir.child(MANAGED_FILE).ifPresent(file -> settings(out, ToolkitScope.MANAGED, file));
            dir.child(MANAGED_DROP_INS).ifPresent(dropIns -> {
                for (Location file : out.list(dropIns)) {
                    if (isDropIn(file.name())) {
                        settings(out, ToolkitScope.MANAGED, file);
                    }
                }
            });
        });
        for (ClaudeLayout.Root root : layout.roots()) {
            if (root.scope() == ToolkitScope.PROJECT) {
                root.dir().child(LOCAL_FILE).ifPresent(file -> settings(out, ToolkitScope.LOCAL, file));
            }
            root.dir().child(SETTINGS_FILE).ifPresent(file -> settings(out, root.scope(), file));
        }
    }

    // 문서: "Claude Code ignores hidden files and files that don't end in .json"
    private static boolean isDropIn(String name) {
        return name.endsWith(".json") && !name.startsWith(".");
    }

    private static void settings(Collector out, ToolkitScope scope, Location file) {
        out.read(file, SettingsJson::read).ifPresent(read -> {
            read.error().ifPresent(error -> out.malformed(file, error));
            read.value().ifPresent(settings -> {
                for (SettingsJson.Entry hook : settings.hooks()) {
                    out.item(ClaudeItems.item(
                            ToolkitKind.HOOK, scope, hook.name(), Optional.empty(), file, hook.attributes()));
                }
                for (SettingsJson.Entry rule : settings.permissions()) {
                    out.item(ClaudeItems.item(
                            ToolkitKind.PERMISSION, scope, rule.name(), Optional.empty(), file, rule.attributes()));
                }
            });
        });
    }
}
