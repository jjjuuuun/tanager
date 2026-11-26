package dev.tanager.domain.history;

import static dev.tanager.domain.history.DirectoryTreeTest.env;
import static dev.tanager.domain.history.DirectoryTreeTest.folder;
import static dev.tanager.domain.history.DirectoryTreeTest.history;
import static dev.tanager.domain.history.DirectoryTreeTest.labels;
import static dev.tanager.domain.history.DirectoryTreeTest.path;
import static dev.tanager.domain.history.DirectoryTreeTest.project;
import static dev.tanager.domain.history.DirectoryTreeTest.session;
import static dev.tanager.domain.history.DirectoryTreeTest.sessionAtHome;
import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DirectoryTreeSearchTest {

    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment WSL_U = new ShellEnvironment.Wsl("U");

    private static TreeQuery q(String text) {
        return new TreeQuery(text, false, List.of(WSL_U));
    }

    private static List<TreeNode.Folder> allFolders(List<TreeNode> nodes) {
        List<TreeNode.Folder> out = new ArrayList<>();
        for (TreeNode n : nodes) {
            if (n instanceof TreeNode.Folder f) {
                out.add(f);
                out.addAll(allFolders(f.children()));
            }
        }
        return out;
    }

    private static List<WorkspacePath> matchedPaths(HistoryTree tree, ShellEnvironment e) {
        return allFolders(env(tree, e).children()).stream()
                .filter(TreeNode.Folder::matched)
                .map(TreeNode.Folder::path)
                .toList();
    }

    private static WorkHistory aliased(WorkHistory h, WorkspacePath p, String alias) {
        return h.alias(p, Optional.of(alias)).value().orElseThrow();
    }

    @Nested
    class 일치_규칙 {

        @Test
        @DisplayName("구분자 없는 검색어는 폴더 이름에서 찾고 맞은 잎과 조상만 남기며 압축은 거른 뒤 다시 계산한다")
        void nameSearchKeepsMatchesAndAncestors() {
            // given
            WorkspacePath api = path(WSL_U, "/home/me/proj/api");
            WorkspacePath client = path(WSL_U, "/home/me/apiclient");
            WorkspacePath web = path(WSL_U, "/srv/web");

            // when
            HistoryTree tree = project(history(api, client, web), List.of(), q("api"));

            // then
            List<TreeNode> roots = env(tree, WSL_U).children();
            assertThat(labels(roots)).containsExactly("/home/me");
            TreeNode.Folder me = folder(roots.get(0));
            assertThat(me.matched()).isFalse();
            assertThat(labels(me.children())).containsExactly("apiclient", "proj/api");
            assertThat(matchedPaths(tree, WSL_U)).containsExactlyInAnyOrder(api, client);
        }

        @Test
        @DisplayName("거른 뒤 형제가 하나만 남으면 조상과 한 줄로 다시 합쳐진다")
        void compressionRecomputedAfterFilter() {
            // given
            WorkHistory h = history(path(WSL_U, "/srv/web"), path(WSL_U, "/srv/db"));

            // when
            HistoryTree tree = project(h, List.of(), q("web"));

            // then
            assertThat(labels(env(tree, WSL_U).children())).containsExactly("/srv/web");
        }

        @Test
        @DisplayName("방문 있는 조상은 맞지 않아도 남고 matched 가 아니다")
        void visitedAncestorStaysUnmatched() {
            // given
            WorkspacePath me = path(WSL_U, "/home/me");
            WorkspacePath api = path(WSL_U, "/home/me/proj/api");

            // when
            HistoryTree tree = project(history(me, api), List.of(), q("api"));

            // then
            TreeNode.Folder root = folder(env(tree, WSL_U).children().get(0));
            assertThat(root.path()).isEqualTo(me);
            assertThat(root.visit()).isPresent();
            assertThat(root.matched()).isFalse();
            assertThat(labels(root.children())).containsExactly("proj/api");
            assertThat(matchedPaths(tree, WSL_U)).containsExactly(api);
        }

        @Test
        @DisplayName("맞은 폴더 아래의 맞지 않는 폴더는 남기지 않는다")
        void nonMatchingDescendantsOfMatchAreDropped() {
            // given
            WorkspacePath proj = path(WSL_U, "/home/me/proj");
            WorkspacePath api = path(WSL_U, "/home/me/proj/api");

            // when
            HistoryTree tree = project(history(proj, api), List.of(), q("proj"));

            // then
            TreeNode.Folder f = folder(env(tree, WSL_U).children().get(0));
            assertThat(f.path()).isEqualTo(proj);
            assertThat(f.matched()).isTrue();
            assertThat(f.children()).isEmpty();
        }

        @Test
        @DisplayName("검색어 home 은 /home 폴더 방문 기록이 있을 때만 그 폴더가 맞고 아래 폴더는 맞지 않는다")
        void homeMatchesOnlyFolderNamedHome() {
            // given
            WorkspacePath home = path(WSL_U, "/home");
            WorkspacePath proj = path(WSL_U, "/home/me/proj");

            // when
            HistoryTree withHome = project(history(home, proj), List.of(), q("home"));
            HistoryTree withoutHome = project(history(proj), List.of(), q("home"));

            // then
            assertThat(matchedPaths(withHome, WSL_U)).containsExactly(home);
            assertThat(folder(env(withHome, WSL_U).children().get(0)).children())
                    .isEmpty();
            assertThat(env(withoutHome, WSL_U).children()).isEmpty();
        }

        @Test
        @DisplayName("구분자가 있는 검색어는 전체 경로에서 찾는다")
        void separatorSearchUsesFullPath() {
            // given
            WorkspacePath api = path(WSL_U, "/home/me/proj/api");
            WorkspacePath other = path(WSL_U, "/srv/proj");

            // when
            HistoryTree tree = project(history(api, other), List.of(), q("me/proj"));

            // then
            assertThat(matchedPaths(tree, WSL_U)).containsExactly(api);
            assertThat(labels(env(tree, WSL_U).children())).containsExactly("/home/me/proj/api");
        }

        @Test
        @DisplayName("Windows 경로는 역슬래시 검색어도 전체 경로에서 대소문자 없이 찾는다")
        void windowsBackslashSearch() {
            // given
            WorkspacePath infra = path(WIN, "C:\\Work\\Infra");
            WorkspacePath other = path(WIN, "D:\\other");

            // when
            HistoryTree tree =
                    project(history(infra, other), List.of(), new TreeQuery("work\\inf", false, List.of(WIN)));

            // then
            assertThat(matchedPaths(tree, WIN)).containsExactly(infra);
        }

        @Test
        @DisplayName("Windows 경로는 슬래시 검색어로도 찾는다")
        void windowsSlashSearch() {
            // given
            WorkspacePath infra = path(WIN, "C:\\work\\infra");
            WorkHistory h = history(infra, path(WIN, "D:\\other"));

            // when
            HistoryTree byTail = project(h, List.of(), new TreeQuery("work/inf", false, List.of(WIN)));
            HistoryTree byDrive = project(h, List.of(), new TreeQuery("c:/work", false, List.of(WIN)));

            // then
            assertThat(matchedPaths(byTail, WIN)).containsExactly(infra);
            assertThat(matchedPaths(byDrive, WIN)).containsExactly(infra);
        }

        @Test
        @DisplayName("WSL 경로는 역슬래시 검색어로 찾지 않는다")
        void wslNotFoundByBackslash() {
            // given
            WorkHistory h = history(path(WSL_U, "/srv/web"));

            // when
            HistoryTree tree = project(h, List.of(), q("srv\\web"));

            // then
            assertThat(env(tree, WSL_U).children()).isEmpty();
        }

        @Test
        @DisplayName("검색 중 방문 없는 부모의 표기는 검색하지 않을 때의 트리와 같다")
        void unvisitedParentKeepsUnfilteredSpelling() {
            // given
            WorkHistory h = history(path(WIN, "C:\\Work\\a"), path(WIN, "C:\\work\\b"), path(WIN, "C:\\work\\bb"));

            // when
            TreeNode.Folder unfiltered = folder(env(project(h, List.of(), new TreeQuery("", false, List.of(WIN))), WIN)
                    .children()
                    .get(0));
            TreeNode.Folder searched = folder(env(project(h, List.of(), new TreeQuery("b", false, List.of(WIN))), WIN)
                    .children()
                    .get(0));

            // then
            assertThat(unfiltered.label()).isEqualTo("C:\\Work");
            assertThat(searched.label()).isEqualTo(unfiltered.label());
            assertThat(searched.path().path()).isEqualTo(unfiltered.path().path());
            assertThat(labels(searched.children())).containsExactly("b", "bb");
        }

        @Test
        @DisplayName("검색어 앞뒤 공백은 무시한다")
        void surroundingWhitespaceIgnored() {
            // given
            WorkHistory h = history(path(WSL_U, "/a/api"), path(WSL_U, "/b/web"));

            // when
            HistoryTree tree = project(h, List.of(), q("  api\t"));

            // then
            assertThat(matchedPaths(tree, WSL_U)).containsExactly(path(WSL_U, "/a/api"));
        }

        @Test
        @DisplayName("검색어가 일치하는 항목이 없으면 환경 노드는 남고 자식이 없다")
        void noMatchLeavesEmptyEnvironment() {
            // when
            HistoryTree tree = project(history(path(WSL_U, "/a/api")), List.of(), q("zzz"));

            // then
            assertThat(tree.environments()).extracting(EnvironmentNode::env).containsExactly(WSL_U);
            assertThat(env(tree, WSL_U).children()).isEmpty();
        }
    }

    @Nested
    class 대소문자_별칭_정규화 {

        @Test
        @DisplayName("검색은 대소문자를 무시한다 — API 와 api 의 결과가 같다")
        void caseInsensitive() {
            // given
            WorkHistory h =
                    history(path(WSL_U, "/home/me/proj/api"), path(WSL_U, "/srv/Api2"), path(WSL_U, "/srv/web"));

            // when
            HistoryTree upper = project(h, List.of(), q("API"));
            HistoryTree lower = project(h, List.of(), q("api"));

            // then
            assertThat(upper).isEqualTo(lower);
            assertThat(matchedPaths(upper, WSL_U)).hasSize(2);
        }

        @Test
        @DisplayName("별칭도 부분 일치로 찾는다")
        void aliasMatches() {
            // given
            WorkspacePath infra = path(WIN, "C:\\work\\infra");
            WorkHistory h = aliased(history(infra, path(WIN, "C:\\work\\web")), infra, "인프라");

            // when
            HistoryTree tree = project(h, List.of(), new TreeQuery("인프", false, List.of(WIN)));

            // then
            assertThat(matchedPaths(tree, WIN)).containsExactly(infra);
        }

        @Test
        @DisplayName("별칭이 있어도 폴더 이름으로 찾을 수 있고 구분자 있는 검색은 별칭도 본다")
        void folderNameAndAliasBothSearchable() {
            // given
            WorkspacePath infra = path(WIN, "C:\\work\\infra");
            WorkHistory h = aliased(history(infra), infra, "Prod 서버");

            // when
            HistoryTree byName = project(h, List.of(), new TreeQuery("infra", false, List.of(WIN)));
            HistoryTree byAliasWithSeparator = project(h, List.of(), new TreeQuery("prod 서", false, List.of(WIN)));
            HistoryTree byPath = project(h, List.of(), new TreeQuery("work\\infra", false, List.of(WIN)));

            // then
            assertThat(matchedPaths(byName, WIN)).containsExactly(infra);
            assertThat(matchedPaths(byAliasWithSeparator, WIN)).containsExactly(infra);
            assertThat(matchedPaths(byPath, WIN)).containsExactly(infra);
        }

        @Test
        @DisplayName("별칭에 구분자가 있는 검색어도 별칭에서 찾는다")
        void aliasMatchedWhenQueryHasSeparator() {
            // given
            WorkspacePath p = path(WSL_U, "/a/b");
            WorkHistory h = aliased(history(p), p, "dev/ops");

            // when
            HistoryTree tree = project(h, List.of(), q("dev/o"));

            // then
            assertThat(matchedPaths(tree, WSL_U)).containsExactly(p);
        }

        @Test
        @DisplayName("NFD 로 들어온 검색어가 NFC 경로와 맞는다")
        void nfdQueryMatchesNfcPath() {
            // given
            String nfc = Normalizer.normalize("한글", Normalizer.Form.NFC);
            String nfd = Normalizer.normalize("한글", Normalizer.Form.NFD);
            WorkspacePath p = path(WSL_U, "/work/" + nfc);
            assertThat(nfd).isNotEqualTo(nfc);

            // when
            HistoryTree tree = project(history(p, path(WSL_U, "/work/other")), List.of(), q(nfd));

            // then
            assertThat(matchedPaths(tree, WSL_U)).containsExactly(p);
        }

        @Test
        @DisplayName("NFD 별칭도 NFC 검색어와 맞는다")
        void nfdAliasMatchesNfcQuery() {
            // given
            WorkspacePath p = path(WSL_U, "/a/b");
            WorkHistory h = aliased(history(p), p, Normalizer.normalize("한글", Normalizer.Form.NFD));

            // when
            HistoryTree tree = project(h, List.of(), q(Normalizer.normalize("한글", Normalizer.Form.NFC)));

            // then
            assertThat(matchedPaths(tree, WSL_U)).containsExactly(p);
        }
    }

    @Nested
    class 빈_검색어 {

        @Test
        @DisplayName("공백뿐인 검색어는 거르지 않은 트리와 같다")
        void blankQueryEqualsUnfiltered() {
            // given
            WorkspacePath p = path(WSL_U, "/home/me/proj");
            WorkHistory h = history(p, path(WSL_U, "/srv/web"));
            List<SessionLeaf> sessions = List.of(session(1, p), sessionAtHome(2, WSL_U));

            // when
            HistoryTree unfiltered = project(h, sessions, q(""));
            HistoryTree blank = project(h, sessions, q(" \t "));

            // then
            assertThat(blank).isEqualTo(unfiltered);
            assertThat(allFolders(env(blank, WSL_U).children())).noneMatch(TreeNode.Folder::matched);
        }
    }

    @Nested
    class 세션과_숨김 {

        @Test
        @DisplayName("맞은 폴더의 세션은 남고 맞지 않은 폴더의 세션은 빠진다")
        void sessionsFollowSurvivingFolders() {
            // given
            WorkspacePath api = path(WSL_U, "/srv/api");
            WorkspacePath web = path(WSL_U, "/srv/web");

            // when
            HistoryTree tree = project(history(api, web), List.of(session(1, api), session(2, web)), q("api"));

            // then
            TreeNode.Folder f = folder(env(tree, WSL_U).children().get(0));
            assertThat(f.path()).isEqualTo(api);
            assertThat(labels(f.children())).containsExactly("#1");
            assertThat(allFolders(env(tree, WSL_U).children())).hasSize(1);
        }

        @Test
        @DisplayName("맞은 폴더 아래의 조상 폴더에 달린 세션은 그 폴더가 남으므로 남는다")
        void sessionOnKeptAncestorStays() {
            // given
            WorkspacePath me = path(WSL_U, "/home/me");
            WorkspacePath api = path(WSL_U, "/home/me/api");

            // when
            HistoryTree tree = project(history(api), List.of(session(1, me)), q("api"));

            // then
            TreeNode.Folder root = folder(env(tree, WSL_U).children().get(0));
            assertThat(root.path()).isEqualTo(me);
            assertThat(root.matched()).isFalse();
            assertThat(labels(root.children())).containsExactly("#1", "api");
        }

        @Test
        @DisplayName("세션만 있는 폴더는 이름이 맞아도 일치하지 않아 사라진다")
        void sessionOnlyFolderIsNeverMatched() {
            // given
            WorkspacePath api = path(WSL_U, "/srv/api");

            // when
            HistoryTree tree = project(history(path(WSL_U, "/srv/web")), List.of(session(1, api)), q("api"));

            // then
            assertThat(env(tree, WSL_U).children()).isEmpty();
        }

        @Test
        @DisplayName("거르기 중 사라진 세션 때문에 남은 폴더가 압축되고 남은 세션은 압축을 막는다")
        void droppedSessionsAllowCompression() {
            // given
            WorkspacePath web = path(WSL_U, "/srv/web");
            WorkspacePath db = path(WSL_U, "/srv/db");

            // when
            HistoryTree tree = project(history(web, db), List.of(session(1, db)), q("web"));

            // then
            assertThat(labels(env(tree, WSL_U).children())).containsExactly("/srv/web");
        }

        @Test
        @DisplayName("cwd 를 모르는 세션은 검색 중에는 환경 아래에서도 빠진다")
        void homeSessionDroppedWhileSearching() {
            // given
            WorkHistory h = history(path(WSL_U, "/srv/api"));

            // when
            HistoryTree searching = project(h, List.of(sessionAtHome(1, WSL_U)), q("api"));

            // then
            assertThat(labels(env(searching, WSL_U).children())).containsExactly("/srv/api");
        }

        @Test
        @DisplayName("숨긴 항목은 showHidden=false 면 이름이 맞아도 찾지 않는다")
        void hiddenNotFoundWhenHidden() {
            // given
            WorkspacePath api = path(WSL_U, "/srv/api");
            WorkHistory h = history(api, path(WSL_U, "/srv/web"))
                    .hide(api, true)
                    .value()
                    .orElseThrow();

            // when
            HistoryTree tree = project(h, List.of(), new TreeQuery("api", false, List.of(WSL_U)));

            // then
            assertThat(env(tree, WSL_U).children()).isEmpty();
        }

        @Test
        @DisplayName("숨긴 항목에 세션이 있어도 방문이 없어 이름이 맞지 않으므로 세션과 함께 빠진다")
        void hiddenWithSessionDropped() {
            // given
            WorkspacePath api = path(WSL_U, "/srv/api");
            WorkHistory h = history(api).hide(api, true).value().orElseThrow();

            // when
            HistoryTree tree = project(h, List.of(session(1, api)), new TreeQuery("api", false, List.of(WSL_U)));

            // then
            assertThat(env(tree, WSL_U).children()).isEmpty();
        }

        @Test
        @DisplayName("showHidden=true 면 숨긴 항목도 찾고 matched 다")
        void hiddenFoundWhenShown() {
            // given
            WorkspacePath api = path(WSL_U, "/srv/api");
            WorkHistory h = history(api).hide(api, true).value().orElseThrow();

            // when
            HistoryTree tree = project(h, List.of(), new TreeQuery("api", true, List.of(WSL_U)));

            // then
            assertThat(matchedPaths(tree, WSL_U)).containsExactly(api);
            assertThat(folder(env(tree, WSL_U).children().get(0)).visit())
                    .hasValueSatisfying(v -> assertThat(v.hidden()).isTrue());
        }
    }
}
