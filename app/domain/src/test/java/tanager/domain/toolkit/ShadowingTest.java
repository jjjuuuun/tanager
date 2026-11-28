package tanager.domain.toolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static tanager.domain.toolkit.ToolkitFixtures.CODEX;
import static tanager.domain.toolkit.ToolkitFixtures.item;
import static tanager.domain.toolkit.ToolkitScope.LOCAL;
import static tanager.domain.toolkit.ToolkitScope.MANAGED;
import static tanager.domain.toolkit.ToolkitScope.PROJECT;
import static tanager.domain.toolkit.ToolkitScope.USER;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 같은 이름이 여러 범위에 있을 때의 가려짐(FR-TK-05, #204). */
class ShadowingTest {
    private static final ScopePrecedence SKILL_PROJECT_FIRST =
            new ScopePrecedence(Map.of(ToolkitKind.SKILL, List.of(PROJECT, USER)));

    @Test
    @DisplayName("우선순위가 낮은 범위의 같은 이름 스킬만 가려지고 목록 순서는 그대로다")
    void lowerScopeIsShadowed() {
        // given
        ToolkitItem user = item(ToolkitKind.SKILL, "tdd", USER);
        ToolkitItem project = item(ToolkitKind.SKILL, "tdd", PROJECT);

        // when
        List<ToolkitItem> marked = Shadowing.mark(List.of(user, project), SKILL_PROJECT_FIRST);

        // then
        assertThat(marked).containsExactly(user.withShadowed(true), project);
    }

    @Test
    @DisplayName("종류가 다르면 이름이 같아도 가리지 않는다")
    void differentKindsDoNotShadow() {
        // given
        ScopePrecedence precedence = new ScopePrecedence(
                Map.of(ToolkitKind.SKILL, List.of(PROJECT, USER), ToolkitKind.COMMAND, List.of(PROJECT, USER)));
        List<ToolkitItem> items =
                List.of(item(ToolkitKind.SKILL, "tdd", PROJECT), item(ToolkitKind.COMMAND, "tdd", USER));

        // when
        List<ToolkitItem> marked = Shadowing.mark(items, precedence);

        // then
        assertThat(marked).noneMatch(ToolkitItem::shadowed);
    }

    @Test
    @DisplayName("표에 없는 종류(메모리)는 모두 적용돼 가리지 않는다")
    void kindsOutsideTableAreNeverShadowed() {
        // given
        List<ToolkitItem> items = List.of(
                item(ToolkitKind.MEMORY, "CLAUDE.md", PROJECT),
                item(ToolkitKind.MEMORY, "CLAUDE.md", USER).withShadowed(true));

        // when
        List<ToolkitItem> marked = Shadowing.mark(items, SKILL_PROJECT_FIRST);

        // then
        assertThat(marked).noneMatch(ToolkitItem::shadowed);
    }

    @Test
    @DisplayName("공급자가 다르면 같은 이름이어도 가리지 않는다")
    void differentProvidersDoNotShadow() {
        // given
        List<ToolkitItem> items =
                List.of(item(ToolkitKind.SKILL, "tdd", PROJECT), item(CODEX, ToolkitKind.SKILL, "tdd", USER));

        // when
        List<ToolkitItem> marked = Shadowing.mark(items, SKILL_PROJECT_FIRST);

        // then
        assertThat(marked).noneMatch(ToolkitItem::shadowed);
    }

    @Test
    @DisplayName("이름은 대소문자를 구분해 비교한다")
    void namesAreCaseSensitive() {
        // given
        List<ToolkitItem> items =
                List.of(item(ToolkitKind.SKILL, "TDD", PROJECT), item(ToolkitKind.SKILL, "tdd", USER));

        // when
        List<ToolkitItem> marked = Shadowing.mark(items, SKILL_PROJECT_FIRST);

        // then
        assertThat(marked).noneMatch(ToolkitItem::shadowed);
    }

    @Test
    @DisplayName("표에 없는 범위는 표의 범위보다 지고, 같은 순위끼리는 먼저 나온 쪽이 이긴다")
    void unlistedScopesLoseAndTiesKeepFirst() {
        // given
        ToolkitItem managed = item(ToolkitKind.SKILL, "tdd", MANAGED);
        ToolkitItem local = item(ToolkitKind.SKILL, "tdd", LOCAL);
        ToolkitItem user = item(ToolkitKind.SKILL, "tdd", USER);
        ToolkitItem firstProject = item(ToolkitKind.SKILL, "x", PROJECT);
        ToolkitItem secondProject = item(ToolkitKind.SKILL, "x", PROJECT);

        // when
        List<ToolkitItem> marked =
                Shadowing.mark(List.of(managed, user, local, firstProject, secondProject), SKILL_PROJECT_FIRST);

        // then
        assertThat(marked)
                .containsExactly(
                        managed.withShadowed(true),
                        user,
                        local.withShadowed(true),
                        firstProject,
                        secondProject.withShadowed(true));
    }

    @Test
    @DisplayName("우선순위 표의 한 종류에 같은 범위가 두 번 나오면 거절하고 표는 바깥 변경을 따라가지 않는다")
    void precedenceInvariants() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ScopePrecedence(Map.of(ToolkitKind.SKILL, List.of(PROJECT, PROJECT))));
        assertThat(SKILL_PROJECT_FIRST.order()).containsOnlyKeys(ToolkitKind.SKILL);
        assertThat(SKILL_PROJECT_FIRST.order().get(ToolkitKind.SKILL)).containsExactly(PROJECT, USER);
    }
}
