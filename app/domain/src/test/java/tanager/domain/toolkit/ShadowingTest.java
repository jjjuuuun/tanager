package tanager.domain.toolkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static tanager.domain.toolkit.ToolkitFixtures.CODEX;
import static tanager.domain.toolkit.ToolkitFixtures.item;
import static tanager.domain.toolkit.ToolkitScope.LOCAL;
import static tanager.domain.toolkit.ToolkitScope.MANAGED;
import static tanager.domain.toolkit.ToolkitScope.PROJECT;
import static tanager.domain.toolkit.ToolkitScope.USER;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 같은 이름이 여러 범위에 있을 때의 가려짐(FR-TK-05, #204 · #247). */
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

    private static final ScopePrecedence SKILL_COMMAND_SHARED = new ScopePrecedence(Map.of(
                    ToolkitKind.SKILL, List.of(MANAGED, USER, PROJECT),
                    ToolkitKind.COMMAND, List.of(MANAGED, USER, PROJECT)))
            .sharing(ScopePrecedence.NameMatch.LOOSE, ToolkitKind.SKILL, ToolkitKind.COMMAND);

    private static final ScopePrecedence MCP_MANAGED_EXCLUSIVE = new ScopePrecedence(
                    Map.of(ToolkitKind.MCP_SERVER, List.of(MANAGED, LOCAL, PROJECT, USER)))
            .exclusive(ToolkitKind.MCP_SERVER, MANAGED);

    @Test
    @DisplayName("이름 공간을 나누는 두 종류는 앞 종류가 범위와 상관없이 이긴다 — 사용자 스킬 deploy 가 프로젝트 커맨드 deploy 를 가린다")
    void sharedNameSpaceEarlierKindWins() {
        // given
        ToolkitItem skill = item(ToolkitKind.SKILL, "deploy", USER);
        ToolkitItem command = item(ToolkitKind.COMMAND, "deploy", PROJECT);
        ToolkitItem managedCommand = item(ToolkitKind.COMMAND, "deploy", MANAGED);

        // when
        List<ToolkitItem> marked = Shadowing.mark(List.of(command, managedCommand, skill), SKILL_COMMAND_SHARED);

        // then
        assertThat(marked).containsExactly(command.withShadowed(true), managedCommand.withShadowed(true), skill);
        assertThat(Shadowing.winnerOf(marked, marked.getFirst(), SKILL_COMMAND_SHARED))
                .contains(skill);
    }

    @Test
    @DisplayName("LOOSE 이름 공간은 대소문자를 무시한다 — 프로젝트 Deploy 와 사용자 deploy 는 같은 이름이다")
    void looseNamesIgnoreCase() {
        // given
        ToolkitItem project = item(ToolkitKind.SKILL, "Deploy", PROJECT);
        ToolkitItem user = item(ToolkitKind.SKILL, "deploy", USER);

        // when
        List<ToolkitItem> marked = Shadowing.mark(List.of(project, user), SKILL_COMMAND_SHARED);

        // then
        assertThat(marked).containsExactly(project.withShadowed(true), user);
        assertThat(Shadowing.winnerOf(marked, marked.getFirst(), SKILL_COMMAND_SHARED))
                .contains(user);
    }

    @Test
    @DisplayName("LOOSE 이름 공간은 공백 · 보이지 않는 글자를 무시하고 전각 글자 · 대시 변형을 기본형으로 본다")
    void looseNamesFoldSpacingInvisibleAndCompatibilityForms() {
        // given
        ToolkitItem user = item(ToolkitKind.SKILL, "deploy-staging", USER);
        List<ToolkitItem> lookAlikes = List.of(
                item(ToolkitKind.SKILL, "ｄｅｐｌｏｙ－ｓｔａｇｉｎｇ", PROJECT),
                item(ToolkitKind.SKILL, "deploy\u2013staging", PROJECT),
                item(ToolkitKind.SKILL, "de\u200Bploy-staging", PROJECT),
                item(ToolkitKind.COMMAND, "deploy - staging", PROJECT));
        List<ToolkitItem> items = new ArrayList<>(lookAlikes);
        items.add(user);

        // when
        List<ToolkitItem> marked = Shadowing.mark(items, SKILL_COMMAND_SHARED);

        // then
        assertThat(marked.subList(0, lookAlikes.size())).allMatch(ToolkitItem::shadowed);
        assertThat(marked.getLast()).isEqualTo(user);
    }

    @Test
    @DisplayName("다른 문자 체계의 닮은 글자는 LOOSE 에서도 다른 이름이다")
    void looseNamesKeepLookAlikeLettersApart() {
        // given — 키릴 문자 е(U+0435)
        List<ToolkitItem> items =
                List.of(item(ToolkitKind.SKILL, "d\u0435ploy", PROJECT), item(ToolkitKind.SKILL, "deploy", USER));

        // when
        List<ToolkitItem> marked = Shadowing.mark(items, SKILL_COMMAND_SHARED);

        // then
        assertThat(marked).noneMatch(ToolkitItem::shadowed);
    }

    @Test
    @DisplayName("독점 범위에 항목이 있으면 그 종류의 다른 범위 항목은 이름이 달라도 가려지고, 가린 쪽은 독점 범위의 항목이다")
    void exclusiveScopeShadowsEveryOtherScope() {
        // given
        ToolkitItem managed = item(ToolkitKind.MCP_SERVER, "a", MANAGED);
        ToolkitItem project = item(ToolkitKind.MCP_SERVER, "b", PROJECT);
        ToolkitItem sameName = item(ToolkitKind.MCP_SERVER, "a", USER);
        ToolkitItem skill = item(ToolkitKind.SKILL, "b", PROJECT);

        // when
        List<ToolkitItem> marked = Shadowing.mark(List.of(project, managed, sameName, skill), MCP_MANAGED_EXCLUSIVE);

        // then
        assertThat(marked).containsExactly(project.withShadowed(true), managed, sameName.withShadowed(true), skill);
        assertThat(Shadowing.winnerOf(marked, marked.getFirst(), MCP_MANAGED_EXCLUSIVE))
                .contains(managed);
        assertThat(Shadowing.winnerOf(marked, marked.get(2), MCP_MANAGED_EXCLUSIVE))
                .contains(managed);
    }

    @Test
    @DisplayName("독점 범위에 항목이 없으면 같은 이름끼리만 가린다")
    void exclusiveScopeWithoutItemsChangesNothing() {
        // given
        ToolkitItem local = item(ToolkitKind.MCP_SERVER, "db", LOCAL);
        ToolkitItem project = item(ToolkitKind.MCP_SERVER, "db", PROJECT);
        ToolkitItem github = item(ToolkitKind.MCP_SERVER, "github", USER);

        // when
        List<ToolkitItem> marked = Shadowing.mark(List.of(project, github, local), MCP_MANAGED_EXCLUSIVE);

        // then
        assertThat(marked).containsExactly(project.withShadowed(true), github, local);
        assertThat(Shadowing.winnerOf(marked, marked.getFirst(), MCP_MANAGED_EXCLUSIVE))
                .contains(local);
    }

    @Test
    @DisplayName("다른 공급자의 독점 범위 항목은 이 공급자의 항목을 가리지 않는다")
    void exclusiveScopeIsPerProvider() {
        // given
        ToolkitItem codexManaged = item(CODEX, ToolkitKind.MCP_SERVER, "a", MANAGED);
        ToolkitItem project = item(ToolkitKind.MCP_SERVER, "b", PROJECT);

        // when
        List<ToolkitItem> marked = Shadowing.mark(List.of(codexManaged, project), MCP_MANAGED_EXCLUSIVE);

        // then
        assertThat(marked).noneMatch(ToolkitItem::shadowed);
    }

    @Test
    @DisplayName("가려지지 않은 항목의 가린 쪽은 empty 다")
    void winnerOfUnshadowedIsEmpty() {
        // given
        List<ToolkitItem> marked =
                Shadowing.mark(List.of(item(ToolkitKind.MCP_SERVER, "a", MANAGED)), MCP_MANAGED_EXCLUSIVE);

        // then
        assertThat(Shadowing.winnerOf(marked, marked.getFirst(), MCP_MANAGED_EXCLUSIVE))
                .isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("이름 공간 · 독점 규칙의 종류는 표에 있어야 하고 한 종류는 이름 공간 하나에만 든다")
    void ruleInvariants() {
        ScopePrecedence skillOnly = SKILL_PROJECT_FIRST;
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        skillOnly.sharing(ScopePrecedence.NameMatch.LOOSE, ToolkitKind.SKILL, ToolkitKind.COMMAND));
        assertThatIllegalArgumentException().isThrownBy(() -> skillOnly.exclusive(ToolkitKind.MCP_SERVER, MANAGED));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SKILL_COMMAND_SHARED.sharing(ScopePrecedence.NameMatch.EXACT, ToolkitKind.SKILL));
        assertThatIllegalArgumentException().isThrownBy(() -> skillOnly.sharing(ScopePrecedence.NameMatch.EXACT));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> skillOnly.sharing(ScopePrecedence.NameMatch.EXACT, ToolkitKind.SKILL, ToolkitKind.SKILL));
        assertThat(SKILL_COMMAND_SHARED.nameSpaces())
                .containsExactly(new ScopePrecedence.NameSpace(
                        List.of(ToolkitKind.SKILL, ToolkitKind.COMMAND), ScopePrecedence.NameMatch.LOOSE));
        assertThat(MCP_MANAGED_EXCLUSIVE.exclusive()).containsExactly(Map.entry(ToolkitKind.MCP_SERVER, MANAGED));
    }
}
