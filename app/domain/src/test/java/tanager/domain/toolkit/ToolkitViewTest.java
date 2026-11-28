package tanager.domain.toolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tanager.domain.toolkit.ToolkitFixtures.CLAUDE;
import static tanager.domain.toolkit.ToolkitFixtures.CODEX;
import static tanager.domain.toolkit.ToolkitFixtures.PROJECT_DIR;
import static tanager.domain.toolkit.ToolkitFixtures.SOURCE;
import static tanager.domain.toolkit.ToolkitFixtures.UBUNTU;
import static tanager.domain.toolkit.ToolkitFixtures.item;
import static tanager.domain.toolkit.ToolkitScope.LOCAL;
import static tanager.domain.toolkit.ToolkitScope.MANAGED;
import static tanager.domain.toolkit.ToolkitScope.PARENT;
import static tanager.domain.toolkit.ToolkitScope.PROJECT;
import static tanager.domain.toolkit.ToolkitScope.USER;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 도구함 보기의 묶음 · 공급자 칩 · 가린 항목(FR-TK-03 · FR-TK-05, #204). */
class ToolkitViewTest {

    private static ToolkitView view(List<ToolkitItem> items, List<ToolkitWarning> warnings) {
        return new ToolkitView(PROJECT_DIR, items, warnings);
    }

    @Test
    @DisplayName("묶음은 종류 → 범위(MANAGED, LOCAL, PROJECT, PARENT, USER) → 이름(대소문자 무시) 순이다")
    void groupedOrder() {
        // given
        ToolkitItem hookUser = item(ToolkitKind.HOOK, "PreToolUse", USER);
        ToolkitItem skillUserB = item(ToolkitKind.SKILL, "beta", USER);
        ToolkitItem skillProjectZ = item(ToolkitKind.SKILL, "Zed", PROJECT);
        ToolkitItem skillManaged = item(ToolkitKind.SKILL, "policy", MANAGED);
        ToolkitItem skillProjectA = item(ToolkitKind.SKILL, "alpha", PROJECT);
        ToolkitItem skillParent = item(ToolkitKind.SKILL, "up", PARENT);
        ToolkitItem skillLocal = item(ToolkitKind.SKILL, "mine", LOCAL);
        ToolkitItem skillUserA = item(ToolkitKind.SKILL, "Alpha", USER);
        ToolkitItem memory = item(ToolkitKind.MEMORY, "CLAUDE.md", PROJECT);
        ToolkitView view = view(
                List.of(
                        hookUser,
                        skillUserB,
                        skillProjectZ,
                        skillManaged,
                        skillProjectA,
                        skillParent,
                        skillLocal,
                        skillUserA,
                        memory),
                List.of());

        // when
        Map<ToolkitKind, Map<ToolkitScope, List<ToolkitItem>>> grouped = view.grouped();

        // then
        assertThat(grouped.keySet()).containsExactly(ToolkitKind.SKILL, ToolkitKind.MEMORY, ToolkitKind.HOOK);
        Map<ToolkitScope, List<ToolkitItem>> skills = grouped.get(ToolkitKind.SKILL);
        assertThat(skills.keySet()).containsExactly(MANAGED, LOCAL, PROJECT, PARENT, USER);
        assertThat(skills.get(PROJECT)).containsExactly(skillProjectA, skillProjectZ);
        assertThat(skills.get(USER)).containsExactly(skillUserA, skillUserB);
        assertThat(grouped.get(ToolkitKind.MEMORY)).containsOnlyKeys(PROJECT);
    }

    @Test
    @DisplayName("대소문자만 다른 이름은 원래 순서를 지킨다")
    void groupedKeepsOriginalOrderOnTies() {
        // given
        ToolkitItem upper = item(ToolkitKind.SKILL, "TDD", PROJECT);
        ToolkitItem lower = item(ToolkitKind.SKILL, "tdd", PROJECT);

        // when
        List<ToolkitItem> group = view(List.of(upper, lower), List.of())
                .grouped()
                .get(ToolkitKind.SKILL)
                .get(PROJECT);

        // then
        assertThat(group).containsExactly(upper, lower);
    }

    @Test
    @DisplayName("묶음 맵은 어느 단계에서도 고칠 수 없다")
    void groupedIsUnmodifiable() {
        // given
        ToolkitItem skill = item(ToolkitKind.SKILL, "tdd", PROJECT);
        Map<ToolkitKind, Map<ToolkitScope, List<ToolkitItem>>> grouped =
                view(List.of(skill), List.of()).grouped();

        // then
        assertThatThrownBy(() -> grouped.put(ToolkitKind.HOOK, Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> grouped.get(ToolkitKind.SKILL).remove(PROJECT))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> grouped.get(ToolkitKind.SKILL).get(PROJECT).add(skill))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("가려진 항목을 가린 항목을 찾고, 가려지지 않은 항목이면 empty 다")
    void winnerOf() {
        // given
        ToolkitItem project = item(ToolkitKind.SKILL, "tdd", PROJECT);
        ToolkitItem user = item(ToolkitKind.SKILL, "tdd", USER).withShadowed(true);
        ToolkitItem otherProvider = item(CODEX, ToolkitKind.SKILL, "tdd", PROJECT);
        ToolkitItem otherKind = item(ToolkitKind.COMMAND, "tdd", PROJECT);
        ToolkitView view = view(List.of(otherProvider, otherKind, user, project), List.of());

        // then
        assertThat(view.winnerOf(user)).contains(project);
        assertThat(view.winnerOf(project)).isEmpty();
    }

    @Test
    @DisplayName("공급자는 항목 · 경고에 나온 순서로 한 번씩 돌려준다")
    void providersInOrder() {
        // given
        ProviderId gemini = new ProviderId("gemini");
        ToolkitView view = view(
                List.of(item(CODEX, ToolkitKind.SKILL, "a", PROJECT), item(ToolkitKind.SKILL, "b", PROJECT)),
                List.of(new ToolkitWarning.ProviderFailed(gemini), new ToolkitWarning.ProviderFailed(CODEX)));

        // then
        assertThat(view.providers()).containsExactly(CODEX, CLAUDE, gemini);
    }

    @Test
    @DisplayName("공급자 칩으로 고르면 그 공급자의 항목과, 공급자를 가리지 않는 경고만 남는다")
    void onlyProvider() {
        // given
        ToolkitItem claude = item(ToolkitKind.SKILL, "a", PROJECT);
        ToolkitItem codex = item(CODEX, ToolkitKind.SKILL, "b", PROJECT);
        ToolkitWarning unreadable = new ToolkitWarning.Unreadable(SOURCE);
        ToolkitWarning malformed = new ToolkitWarning.Malformed(SOURCE, OptionalInt.of(3));
        ToolkitWarning home = new ToolkitWarning.HomeUnknown(UBUNTU);
        ToolkitWarning codexFailed = new ToolkitWarning.ProviderFailed(CODEX);
        ToolkitWarning claudeFailed = new ToolkitWarning.ProviderFailed(CLAUDE);
        ToolkitView view =
                view(List.of(claude, codex), List.of(unreadable, codexFailed, malformed, claudeFailed, home));

        // when
        ToolkitView only = view.only(CLAUDE);

        // then
        assertThat(only.path()).isEqualTo(PROJECT_DIR);
        assertThat(only.items()).containsExactly(claude);
        assertThat(only.warnings()).containsExactly(unreadable, malformed, claudeFailed, home);
    }

    @Test
    @DisplayName("형식 오류 경고의 줄 번호는 1 이상이다")
    void malformedLineIsPositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ToolkitWarning.Malformed(SOURCE, OptionalInt.of(0)));
        assertThat(new ToolkitWarning.Malformed(SOURCE, OptionalInt.empty()).line())
                .isEmpty();
    }
}
