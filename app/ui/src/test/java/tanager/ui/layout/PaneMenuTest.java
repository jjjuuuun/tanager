package tanager.ui.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.layout.Direction;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Rect;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;
import tanager.domain.shared.PaneId;
import tanager.ui.keys.LayoutAction;

class PaneMenuTest {
    private static final Rect AREA = new Rect(0, 0, 800, 600);
    private static final int GAP = 6;
    private static final PaneId P1 = new PaneId("p1");
    private static final PaneId P2 = new PaneId("p2");
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellProfile CMD = profile("cmd", ShellKind.CMD, WIN);
    private static final ShellProfile POWERSHELL = profile("powershell", ShellKind.WINDOWS_POWERSHELL, WIN);
    private static final ShellProfile UBUNTU =
            profile("wsl:Ubuntu", ShellKind.BASH, new ShellEnvironment.Wsl("Ubuntu"));
    private static final List<ShellProfile> ALL = List.of(UBUNTU, POWERSHELL, CMD);

    private static ShellProfile profile(String id, ShellKind kind, ShellEnvironment env) {
        return new ShellProfile(
                new ProfileId(id), id, kind, env, new LaunchTemplate(id + ".exe", List.of()), IntegrationMode.NONE);
    }

    private static final LayoutNode ONE = new LayoutNode.Leaf(P1);
    // p1 | p2
    private static final LayoutNode TWO =
            ONE.split(P1, Orientation.ROW, P2).value().orElseThrow();

    private static PaneMenu.Submenu submenu(List<PaneMenu.Entry> entries, String label) {
        return entries.stream()
                .filter(PaneMenu.Submenu.class::isInstance)
                .map(PaneMenu.Submenu.class::cast)
                .filter(sub -> sub.label().equals(label))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("메뉴 순서: 분할 ▸ · 다른 프로파일로 분할 ▸ · 교환 ▸ · 닫기(테스트 언어는 한국어)")
    void order() {
        // when
        List<PaneMenu.Entry> entries = PaneMenu.entries(TWO, P1, AREA, GAP, Optional.of(CMD), ALL);

        // then
        assertThat(entries).hasSize(4);
        assertThat(entries.get(0)).isEqualTo(submenu(entries, "분할"));
        assertThat(entries.get(1)).isEqualTo(submenu(entries, "다른 프로파일로 분할"));
        assertThat(entries.get(2)).isEqualTo(submenu(entries, "교환"));
        assertThat(entries.get(3))
                .isEqualTo(new PaneMenu.Item(
                        "창 닫기 (Ctrl+Shift+W)", true, new PaneMenu.Command.Act(new LayoutAction.Close())));
    }

    @Test
    @DisplayName("분할 ▸ 위 · 아래 · 왼쪽 · 오른쪽 — 아래 · 오른쪽은 키와 같은 분할, 위 · 왼쪽은 앞쪽에 분할")
    void splitSubmenu() {
        // when
        PaneMenu.Submenu split = submenu(PaneMenu.entries(ONE, P1, AREA, GAP, Optional.of(CMD), ALL), "분할");

        // then
        assertThat(split.enabled()).isTrue();
        assertThat(split.items())
                .containsExactly(
                        new PaneMenu.Item("위", true, new PaneMenu.Command.SplitBefore(Orientation.COLUMN)),
                        new PaneMenu.Item(
                                "아래 (Alt+Shift+-)",
                                true,
                                new PaneMenu.Command.Act(new LayoutAction.Split(Orientation.COLUMN))),
                        new PaneMenu.Item("왼쪽", true, new PaneMenu.Command.SplitBefore(Orientation.ROW)),
                        new PaneMenu.Item(
                                "오른쪽 (Alt+Shift+=)",
                                true,
                                new PaneMenu.Command.Act(new LayoutAction.Split(Orientation.ROW))));
    }

    @Test
    @DisplayName("창 하나면 교환은 비활성이고 그 아래 방향도 모두 비활성")
    void swapDisabledForSinglePane() {
        // when
        PaneMenu.Submenu swap = submenu(PaneMenu.entries(ONE, P1, AREA, GAP, Optional.of(CMD), ALL), "교환");

        // then
        assertThat(swap.enabled()).isFalse();
        assertThat(swap.items()).allSatisfy(item -> assertThat(item.enabled()).isFalse());
    }

    @Test
    @DisplayName("p1 | p2 의 p1 에서 교환은 오른쪽만 활성(키 이름 포함), p2 에서는 왼쪽만")
    void swapEnabledTowardsNeighbor() {
        // when
        PaneMenu.Submenu fromLeft = submenu(PaneMenu.entries(TWO, P1, AREA, GAP, Optional.of(CMD), ALL), "교환");
        PaneMenu.Submenu fromRight = submenu(PaneMenu.entries(TWO, P2, AREA, GAP, Optional.of(CMD), ALL), "교환");

        // then
        assertThat(fromLeft.enabled()).isTrue();
        assertThat(fromLeft.items())
                .containsExactly(
                        new PaneMenu.Item("위", false, new PaneMenu.Command.Act(new LayoutAction.Swap(Direction.UP))),
                        new PaneMenu.Item("아래", false, new PaneMenu.Command.Act(new LayoutAction.Swap(Direction.DOWN))),
                        new PaneMenu.Item(
                                "왼쪽 (Ctrl+Shift+←)",
                                false,
                                new PaneMenu.Command.Act(new LayoutAction.Swap(Direction.LEFT))),
                        new PaneMenu.Item(
                                "오른쪽 (Ctrl+Shift+→)",
                                true,
                                new PaneMenu.Command.Act(new LayoutAction.Swap(Direction.RIGHT))));
        assertThat(fromRight.items()).extracting(PaneMenu.Item::enabled).containsExactly(false, false, true, false);
    }

    @Test
    @DisplayName("다른 프로파일로 분할: 같은 환경의 다른 셸만(탐지 순서), 이 창의 셸은 빠진다")
    void otherProfilesOfSameEnvironment() {
        // when
        PaneMenu.Submenu others = submenu(PaneMenu.entries(ONE, P1, AREA, GAP, Optional.of(CMD), ALL), "다른 프로파일로 분할");

        // then
        assertThat(others.enabled()).isTrue();
        assertThat(others.items())
                .containsExactly(new PaneMenu.Item("powershell", true, new PaneMenu.Command.SplitWith(POWERSHELL)));
    }

    @Test
    @DisplayName("이 창의 셸을 모르거나(선택 화면) 같은 환경에 다른 셸이 없으면 다른 프로파일로 분할은 비활성")
    void otherProfilesDisabled() {
        // when
        PaneMenu.Submenu unknown = submenu(PaneMenu.entries(ONE, P1, AREA, GAP, Optional.empty(), ALL), "다른 프로파일로 분할");
        PaneMenu.Submenu alone = submenu(PaneMenu.entries(ONE, P1, AREA, GAP, Optional.of(UBUNTU), ALL), "다른 프로파일로 분할");

        // then
        assertThat(unknown.enabled()).isFalse();
        assertThat(unknown.items()).isEmpty();
        assertThat(alone.enabled()).isFalse();
    }

    @Test
    @DisplayName("없는 창이면 빈 메뉴(닫힌 창의 늦은 우클릭)")
    void unknownPane() {
        // when
        List<PaneMenu.Entry> entries = PaneMenu.entries(ONE, new PaneId("p9"), AREA, GAP, Optional.of(CMD), ALL);

        // then
        assertThat(entries).isEmpty();
    }
}
