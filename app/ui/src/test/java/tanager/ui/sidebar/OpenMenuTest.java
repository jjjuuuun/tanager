package tanager.ui.sidebar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.in.OpenChoices;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.IntegrationMode;
import tanager.domain.session.LaunchTemplate;
import tanager.domain.session.ProfileId;
import tanager.domain.session.ShellKind;
import tanager.domain.session.ShellProfile;

/** 노드의 열기 메뉴 항목(순수, #91, SDD §8.4). */
class OpenMenuTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellProfile WSL_U = profile("wsl:U", "Ubuntu", new ShellEnvironment.Wsl("U"));
    private static final ShellProfile POWERSHELL = profile("powershell", "Windows PowerShell", WIN);
    private static final ShellProfile CMD = profile("cmd", "명령 프롬프트", WIN);
    private static final WorkspacePath WORK =
            WorkspacePath.of(WIN, "C:\\work").value().orElseThrow();
    private static final MenuTarget FOLDER = new MenuTarget.Folder(WORK, Optional.empty());

    private static ShellProfile profile(String id, String name, ShellEnvironment env) {
        return new ShellProfile(
                new ProfileId(id), name, ShellKind.CMD, env, new LaunchTemplate("x", List.of()), IntegrationMode.NONE);
    }

    @Test
    @DisplayName("열 수 있는 폴더: 여기서 열기 ▸ 오른쪽 · 아래, 다른 프로파일로 열기 ▸ 후보 셸들")
    void folderWithChoices() {
        // when
        List<MenuEntry> entries =
                OpenMenu.entries(FOLDER, new OpenChoices(Optional.of(CMD), List.of(WSL_U, POWERSHELL)));

        // then
        Submenu here = (Submenu) entries.get(0);
        assertThat(here.enabled()).isTrue();
        assertThat(here.items())
                .extracting(MenuItem::action)
                .containsExactly(MenuAction.OPEN_RIGHT, MenuAction.OPEN_DOWN);
        Submenu other = (Submenu) entries.get(1);
        assertThat(other.enabled()).isTrue();
        assertThat(other.items()).extracting(MenuItem::label).containsExactly("Ubuntu", "Windows PowerShell");
        assertThat(other.items())
                .extracting(MenuItem::profile)
                .containsExactly(Optional.of(WSL_U.id()), Optional.of(POWERSHELL.id()));
        assertThat(other.items()).allMatch(item -> item.action() == MenuAction.OPEN_WITH);
    }

    @Test
    @DisplayName("다른 프로파일 후보가 없으면(WSL 홈) 그 메뉴만 비활성이고 이유가 있다")
    void noOtherProfiles() {
        // when
        List<MenuEntry> entries = OpenMenu.entries(FOLDER, new OpenChoices(Optional.of(WSL_U), List.of()));

        // then
        Submenu other = (Submenu) entries.get(1);
        assertThat(other.enabled()).isFalse();
        assertThat(other.reason()).isPresent();
        assertThat(((Submenu) entries.get(0)).enabled()).isTrue();
    }

    @Test
    @DisplayName("그 환경의 셸이 없으면(지운 배포판) 두 메뉴 모두 비활성이고 이유는 배포판을 찾을 수 없다는 것이다")
    void noShellForEnvironment() {
        // given
        WorkspacePath gone = WorkspacePath.of(new ShellEnvironment.Wsl("Z"), "/home/me")
                .value()
                .orElseThrow();

        // when
        List<MenuEntry> entries = OpenMenu.entries(
                new MenuTarget.Folder(gone, Optional.empty()), new OpenChoices(Optional.empty(), List.of()));

        // then
        assertThat(entries)
                .allSatisfy(entry -> assertThat(((Submenu) entry).enabled()).isFalse());
        assertThat(((Submenu) entries.get(0)).reason())
                .hasValueSatisfying(r -> assertThat(r).contains("Z"));
    }

    @Test
    @DisplayName("환경 노드는 새 창 하나, 세션 노드는 열기 항목이 없다(세션 메뉴는 #92)")
    void environmentAndSession() {
        assertThat(OpenMenu.entries(
                        new MenuTarget.Environment(WIN, false), new OpenChoices(Optional.empty(), List.of())))
                .containsExactly(new MenuItem(MenuAction.NEW_PANE, tanager.ui.i18n.Msg.MENU_NEW_PANE.text(), true));
        assertThat(OpenMenu.entries(
                        new MenuTarget.Session(Optional.of(WORK)), new OpenChoices(Optional.empty(), List.of())))
                .isEmpty();
    }
}
