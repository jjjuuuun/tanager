package dev.tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tanager.domain.shared.PaneId;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DirectoryTreeTest {

    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment WSL_U = new ShellEnvironment.Wsl("U");
    private static final ShellEnvironment WSL_A = new ShellEnvironment.Wsl("A");
    private static final ShellEnvironment WSL_B = new ShellEnvironment.Wsl("B");
    private static final ShellEnvironment WSL_Z = new ShellEnvironment.Wsl("Z");
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    static WorkHistory history(WorkspacePath... visited) {
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (WorkspacePath p : visited) {
            h = h.recordVisit(p, T);
        }
        return h;
    }

    static SessionLeaf session(int number, WorkspacePath cwd) {
        return new SessionLeaf(
                new PaneId("p" + number), number, "bash", cwd.env(), Optional.of(cwd), new SessionStatus.Running());
    }

    // cwd 를 아직 모르는 세션(홈에서 띄워 OSC 가 오기 전).
    static SessionLeaf sessionAtHome(int number, ShellEnvironment env) {
        return new SessionLeaf(
                new PaneId("p" + number), number, "bash", env, Optional.empty(), new SessionStatus.Running());
    }

    static TreeQuery query(ShellEnvironment... order) {
        return new TreeQuery("", false, List.of(order));
    }

    static HistoryTree project(WorkHistory h, List<SessionLeaf> sessions, TreeQuery q) {
        return DirectoryTree.project(h, sessions, q);
    }

    static EnvironmentNode env(HistoryTree tree, ShellEnvironment env) {
        return tree.environments().stream()
                .filter(e -> e.env().equals(env))
                .findFirst()
                .orElseThrow();
    }

    static TreeNode.Folder folder(TreeNode node) {
        return (TreeNode.Folder) node;
    }

    static List<String> labels(List<TreeNode> nodes) {
        return nodes.stream()
                .map(n -> switch (n) {
                    case TreeNode.Folder f -> f.label();
                    case TreeNode.Session s -> "#" + s.session().number();
                })
                .toList();
    }

    @Nested
    class 기본_투영 {

        @Test
        @DisplayName("방문 경로 하나는 환경 아래 전체 경로 한 줄(방문 노드 하나)로 압축된다")
        void singlePathCompressesToOneLine() {
            // given
            WorkspacePath p = path(WSL_U, "/mnt/c/Users/user/Desktop/Tanager");

            // when
            HistoryTree tree = project(history(p), List.of(), query(WSL_U));

            // then
            List<TreeNode> children = env(tree, WSL_U).children();
            assertThat(children).hasSize(1);
            TreeNode.Folder f = folder(children.get(0));
            assertThat(f.label()).isEqualTo("/mnt/c/Users/user/Desktop/Tanager");
            assertThat(f.path()).isEqualTo(p);
            assertThat(f.visit()).hasValueSatisfying(v -> assertThat(v.path()).isEqualTo(p));
            assertThat(f.children()).isEmpty();
        }

        @Test
        @DisplayName("형제 경로가 생기면 공통 부모 아래에 자식 둘로 갈라진다")
        void siblingsSplitUnderCommonParent() {
            // when
            HistoryTree tree = project(
                    history(
                            path(WSL_U, "/mnt/c/Users/user/Desktop/Tanager"),
                            path(WSL_U, "/mnt/c/Users/user/Desktop/infra")),
                    List.of(),
                    query(WSL_U));

            // then
            TreeNode.Folder parent = folder(env(tree, WSL_U).children().get(0));
            assertThat(parent.label()).isEqualTo("/mnt/c/Users/user/Desktop");
            assertThat(parent.visit()).isEmpty();
            assertThat(labels(parent.children())).containsExactly("infra", "Tanager");
        }

        @Test
        @DisplayName("방문한 중간 노드는 압축하지 않고 아래 경로만 압축한다")
        void visitedIntermediateIsKept() {
            // when
            HistoryTree tree = project(
                    history(path(WSL_U, "/home/me"), path(WSL_U, "/home/me/proj/api")), List.of(), query(WSL_U));

            // then
            TreeNode.Folder me = folder(env(tree, WSL_U).children().get(0));
            assertThat(me.label()).isEqualTo("/home/me");
            assertThat(me.visit()).isPresent();
            assertThat(labels(me.children())).containsExactly("proj/api");
            TreeNode.Folder api = folder(me.children().get(0));
            assertThat(api.path()).isEqualTo(path(WSL_U, "/home/me/proj/api"));
            assertThat(api.visit()).isPresent();
        }

        @Test
        @DisplayName("Windows 는 드라이브 루트와 UNC 루트가 각각 한 줄로 환경 아래 나란히 선다")
        void windowsRoots() {
            // when
            HistoryTree tree = project(
                    history(path(WIN, "C:\\work"), path(WIN, "\\\\wsl.localhost\\U\\home")), List.of(), query(WIN));

            // then
            assertThat(labels(env(tree, WIN).children())).containsExactly("C:\\work", "\\\\wsl.localhost\\U\\home");
        }

        @Test
        @DisplayName("Windows 압축 라벨은 드라이브 루트에 이어 붙이고 이후 세그먼트는 '\\' 로 잇는다")
        void windowsCompressedLabelUsesBackslash() {
            // when
            HistoryTree tree = project(history(path(WIN, "C:\\Users\\me\\src")), List.of(), query(WIN));

            // then
            assertThat(labels(env(tree, WIN).children())).containsExactly("C:\\Users\\me\\src");
        }

        @Test
        @DisplayName("루트에서 갈라지면 루트 노드(라벨 '/') 아래에 압축된 가지가 달린다")
        void rootWithSeveralBranches() {
            // when
            HistoryTree tree = project(
                    history(path(WSL_U, "/home/me"), path(WSL_U, "/tmp/x"), path(WSL_U, "/tmp/y")),
                    List.of(),
                    query(WSL_U));

            // then
            TreeNode.Folder root = folder(env(tree, WSL_U).children().get(0));
            assertThat(root.label()).isEqualTo("/");
            assertThat(root.visit()).isEmpty();
            assertThat(labels(root.children())).containsExactly("home/me", "tmp");
            TreeNode.Folder tmp = folder(root.children().get(1));
            assertThat(labels(tmp.children())).containsExactly("x", "y");
        }

        @Test
        @DisplayName("루트 자체를 방문했으면 루트 노드가 방문 노드다")
        void visitedRoot() {
            // when
            HistoryTree tree = project(history(path(WSL_U, "/"), path(WSL_U, "/etc")), List.of(), query(WSL_U));

            // then
            TreeNode.Folder root = folder(env(tree, WSL_U).children().get(0));
            assertThat(root.label()).isEqualTo("/");
            assertThat(root.visit()).isPresent();
            assertThat(labels(root.children())).containsExactly("etc");
        }

        @Test
        @DisplayName("빈 이력 · 세션 없음이면 순서 목록의 환경마다 빈 환경 노드가 있다")
        void emptyEnvironmentsStillListed() {
            // when
            HistoryTree tree = project(history(), List.of(), query(WSL_A, WIN));

            // then
            assertThat(tree.environments()).extracting(EnvironmentNode::env).containsExactly(WSL_A, WIN);
            assertThat(tree.environments())
                    .allSatisfy(e -> assertThat(e.children()).isEmpty());
        }

        @Test
        @DisplayName("환경이 다르면 같은 경로 문자열이어도 각 환경 아래 따로 선다")
        void sameTextDifferentEnvironments() {
            // when
            HistoryTree tree =
                    project(history(path(WSL_A, "/work"), path(WSL_B, "/work")), List.of(), query(WSL_A, WSL_B));

            // then
            assertThat(labels(env(tree, WSL_A).children())).containsExactly("/work");
            assertThat(labels(env(tree, WSL_B).children())).containsExactly("/work");
        }
    }

    @Nested
    class 대소문자 {

        @Test
        @DisplayName("Windows 에서 대소문자만 다른 C:\\Work\\a 와 C:\\work\\b 는 같은 Work 노드 아래에 있다")
        void windowsCaseOnlyDifferenceSharesNode() {
            // when
            HistoryTree tree =
                    project(history(path(WIN, "C:\\Work\\a"), path(WIN, "C:\\work\\b")), List.of(), query(WIN));

            // then
            List<TreeNode> top = env(tree, WIN).children();
            assertThat(top).hasSize(1);
            TreeNode.Folder work = folder(top.get(0));
            assertThat(work.label()).isEqualTo("C:\\Work");
            assertThat(work.path()).isEqualTo(path(WIN, "C:\\work"));
            assertThat(work.children()).hasSize(2);
            assertThat(labels(work.children())).containsExactly("a", "b");
        }

        @Test
        @DisplayName("압축 라벨은 끝 노드 경로에서 잘라 만들어 path 와 글자까지 같다")
        void compressedLabelMatchesPathExactly() {
            // given — C:\Work\a 노드를 먼저 만든 뒤 대소문자가 다른 C:\work\A 로 방문한다
            WorkHistory h = history(path(WIN, "C:\\Work\\a\\deep"), path(WIN, "C:\\work\\A"));

            // when
            HistoryTree tree = project(h, List.of(), query(WIN));

            // then
            TreeNode.Folder a = folder(env(tree, WIN).children().get(0));
            assertThat(a.path().path()).isEqualTo("C:\\work\\A");
            assertThat(a.label()).isEqualTo("C:\\work\\A");
            assertThat(labels(a.children())).containsExactly("deep");
        }

        @Test
        @DisplayName("방문 없는 중간 노드는 그 아래 방문 경로 중 문자열 순서로 가장 앞선 것의 표기를 따른다")
        void unvisitedNodeTakesSpellingOfFirstVisitBelow() {
            // given — 'W' < 'w' 이므로 C:\Work\b 가 앞선다
            WorkHistory h = history(path(WIN, "C:\\work\\a"), path(WIN, "C:\\Work\\b"));

            // when
            HistoryTree tree = project(h, List.of(), query(WIN));

            // then
            TreeNode.Folder work = folder(env(tree, WIN).children().get(0));
            assertThat(work.label()).isEqualTo("C:\\Work");
            assertThat(work.path().path()).isEqualTo("C:\\Work");
        }

        @Test
        @DisplayName("WSL 은 대소문자만 다른 폴더를 별개 노드로 둔다")
        void wslCaseDiffersStaySeparate() {
            // when
            HistoryTree tree =
                    project(history(path(WSL_U, "/Work/a"), path(WSL_U, "/work/b")), List.of(), query(WSL_U));

            // then
            TreeNode.Folder root = folder(env(tree, WSL_U).children().get(0));
            assertThat(root.label()).isEqualTo("/");
            // /Work · /work 는 방문 없는 외길이라 자식과 합쳐진다(압축 규칙) — 그래도 둘은 별개 노드다
            assertThat(labels(root.children())).containsExactly("Work/a", "work/b");
        }
    }

    @Nested
    class 세션_매달기 {

        @Test
        @DisplayName("방문 기록 없는 cwd 의 세션은 그 경로 노드를 만들고 그 아래에 달린다")
        void sessionCreatesNodeForUnvisitedCwd() {
            // given
            WorkspacePath tmp = path(WSL_U, "/tmp/x");

            // when
            HistoryTree tree = project(history(), List.of(session(2, tmp)), query(WSL_U));

            // then
            TreeNode.Folder f = folder(env(tree, WSL_U).children().get(0));
            assertThat(f.label()).isEqualTo("/tmp/x");
            assertThat(f.path()).isEqualTo(tmp);
            assertThat(f.visit()).isEmpty();
            assertThat(f.children()).hasSize(1);
            assertThat(f.children().get(0))
                    .isInstanceOfSatisfying(
                            TreeNode.Session.class,
                            s -> assertThat(s.session().number()).isEqualTo(2));
        }

        @Test
        @DisplayName("cwd 가 비어 있는 세션은 환경 노드 바로 아래에 달린다")
        void sessionWithoutCwdHangsUnderEnvironment() {
            // when
            HistoryTree tree = project(history(), List.of(sessionAtHome(1, WSL_U)), query(WSL_U));

            // then
            assertThat(labels(env(tree, WSL_U).children())).containsExactly("#1");
        }

        @Test
        @DisplayName("세션이 달린 중간 노드는 압축하지 않는다")
        void sessionPreventsCompression() {
            // when
            HistoryTree tree =
                    project(history(path(WSL_U, "/a/b/c")), List.of(session(1, path(WSL_U, "/a/b"))), query(WSL_U));

            // then
            TreeNode.Folder ab = folder(env(tree, WSL_U).children().get(0));
            assertThat(ab.label()).isEqualTo("/a/b");
            assertThat(labels(ab.children())).containsExactly("#1", "c");
        }

        @Test
        @DisplayName("방문한 폴더의 세션은 그 폴더의 방문 노드 아래에 달린다")
        void sessionUnderVisitedFolder() {
            // given
            WorkspacePath p = path(WSL_U, "/home/me");

            // when
            HistoryTree tree = project(history(p), List.of(session(3, p)), query(WSL_U));

            // then
            TreeNode.Folder f = folder(env(tree, WSL_U).children().get(0));
            assertThat(f.visit()).isPresent();
            assertThat(labels(f.children())).containsExactly("#3");
        }

        @Test
        @DisplayName("종료된 세션도 그대로 달리고 상태를 보존한다")
        void exitedSessionKeptWithStatus() {
            // given
            WorkspacePath p = path(WSL_U, "/a");
            SessionLeaf exited =
                    new SessionLeaf(new PaneId("p9"), 9, "bash", WSL_U, Optional.of(p), new SessionStatus.Exited(2));

            // when
            HistoryTree tree = project(history(), List.of(exited), query(WSL_U));

            // then
            TreeNode.Folder f = folder(env(tree, WSL_U).children().get(0));
            assertThat(f.children().get(0))
                    .isInstanceOfSatisfying(
                            TreeNode.Session.class,
                            s -> assertThat(s.session().status()).isEqualTo(new SessionStatus.Exited(2)));
        }

        @Test
        @DisplayName("Windows 에서 대소문자만 다른 cwd 의 세션은 같은 방문 노드에 달린다")
        void sessionCwdCaseInsensitiveOnWindows() {
            // when
            HistoryTree tree =
                    project(history(path(WIN, "C:\\Work")), List.of(session(1, path(WIN, "c:\\work"))), query(WIN));

            // then
            List<TreeNode> top = env(tree, WIN).children();
            assertThat(top).hasSize(1);
            TreeNode.Folder f = folder(top.get(0));
            assertThat(f.visit()).isPresent();
            assertThat(labels(f.children())).containsExactly("#1");
        }

        @Test
        @DisplayName("번호가 같은 세션은 창 ID 순으로 놓는다")
        void sameNumberOrderedByPane() {
            // given
            WorkspacePath p = path(WSL_U, "/a");
            SessionLeaf b =
                    new SessionLeaf(new PaneId("pb"), 1, "bash", WSL_U, Optional.of(p), new SessionStatus.Running());
            SessionLeaf a =
                    new SessionLeaf(new PaneId("pa"), 1, "bash", WSL_U, Optional.of(p), new SessionStatus.Running());

            // when
            HistoryTree tree = project(history(), List.of(b, a), query(WSL_U));

            // then
            TreeNode.Folder f = folder(env(tree, WSL_U).children().get(0));
            assertThat(f.children())
                    .extracting(n -> ((TreeNode.Session) n).session().pane().value())
                    .containsExactly("pa", "pb");
        }

        @Test
        @DisplayName("세션은 자기 환경 아래에만 달린다")
        void sessionStaysInOwnEnvironment() {
            // when
            HistoryTree tree =
                    project(history(), List.of(sessionAtHome(1, WSL_A), sessionAtHome(2, WIN)), query(WSL_A, WIN));

            // then
            assertThat(labels(env(tree, WSL_A).children())).containsExactly("#1");
            assertThat(labels(env(tree, WIN).children())).containsExactly("#2");
        }
    }

    @Nested
    class 정렬 {

        @Test
        @DisplayName("폴더 자식은 세션(번호순)이 먼저, 그다음 폴더(라벨 대소문자 무시 순)")
        void sessionsBeforeFoldersSorted() {
            // given
            WorkspacePath root = path(WSL_U, "/r");

            // when
            HistoryTree tree = project(
                    history(path(WSL_U, "/r/b1"), path(WSL_U, "/r/Alpha"), path(WSL_U, "/r/c"), root),
                    List.of(session(5, root), session(2, root)),
                    query(WSL_U));

            // then
            TreeNode.Folder r = folder(env(tree, WSL_U).children().get(0));
            assertThat(labels(r.children())).containsExactly("#2", "#5", "Alpha", "b1", "c");
        }

        @Test
        @DisplayName("환경 노드 바로 아래도 세션이 먼저, 그다음 폴더")
        void environmentLevelOrdering() {
            // when
            HistoryTree tree = project(
                    history(path(WSL_U, "/x")),
                    List.of(sessionAtHome(4, WSL_U), sessionAtHome(3, WSL_U)),
                    query(WSL_U));

            // then
            assertThat(labels(env(tree, WSL_U).children())).containsExactly("#3", "#4", "/x");
        }

        @Test
        @DisplayName("압축된 라벨 기준으로 정렬한다")
        void sortsByCompressedLabel() {
            // when
            HistoryTree tree = project(
                    history(path(WSL_U, "/r/b/z"), path(WSL_U, "/r/a"), path(WSL_U, "/r/C")), List.of(), query(WSL_U));

            // then
            TreeNode.Folder r = folder(env(tree, WSL_U).children().get(0));
            assertThat(labels(r.children())).containsExactly("a", "b/z", "C");
        }

        @Test
        @DisplayName("대소문자만 다른 라벨은 일반 문자열 순서로 가려 항상 같은 순서가 된다")
        void caseTieBreak() {
            // when
            HistoryTree tree = project(
                    history(path(WSL_U, "/r/b"), path(WSL_U, "/r/B"), path(WSL_U, "/r/a")), List.of(), query(WSL_U));

            // then
            TreeNode.Folder r = folder(env(tree, WSL_U).children().get(0));
            assertThat(labels(r.children())).containsExactly("a", "B", "b");
        }

        @Test
        @DisplayName("밑줄로 시작하는 폴더는 영문자보다 앞선다(대문자로 접지 않는다)")
        void underscoreSortsBeforeLetters() {
            // given
            WorkHistory h = history(path(WSL_U, "/r/Beta"), path(WSL_U, "/r/_archive"), path(WSL_U, "/r/alpha"));

            // when
            HistoryTree tree = project(h, List.of(), query(WSL_U));

            // then
            TreeNode.Folder r = folder(env(tree, WSL_U).children().get(0));
            assertThat(labels(r.children())).containsExactly("_archive", "alpha", "Beta");
        }

        @Test
        @DisplayName("NFD 라벨도 NFC 로 맞춰 정렬한다")
        void nfdLabelSortedAsNfc() {
            // given
            String sky = Normalizer.normalize("하늘", Normalizer.Form.NFD);
            String river = Normalizer.normalize("가람", Normalizer.Form.NFC);
            WorkHistory h = history(path(WSL_U, "/r/" + sky), path(WSL_U, "/r/" + river));

            // when
            HistoryTree tree = project(h, List.of(), query(WSL_U));

            // then
            TreeNode.Folder r = folder(env(tree, WSL_U).children().get(0));
            assertThat(labels(r.children())).containsExactly(river, sky);
        }

        @Test
        @DisplayName("압축 라벨은 세그먼트 단위로 비교해 구분자가 순서에 끼지 않는다 — Windows 와 WSL 이 같은 순서")
        void segmentWiseOrderIgnoresSeparator() {
            // given
            WorkHistory wsl = history(path(WSL_U, "/r/a0"), path(WSL_U, "/r/a/b"), path(WSL_U, "/r/x"));
            WorkHistory win = history(path(WIN, "C:\\r\\a0"), path(WIN, "C:\\r\\a\\b"), path(WIN, "C:\\r\\x"));

            // when
            TreeNode.Folder wslR = folder(
                    env(project(wsl, List.of(), query(WSL_U)), WSL_U).children().get(0));
            TreeNode.Folder winR = folder(
                    env(project(win, List.of(), query(WIN)), WIN).children().get(0));

            // then
            assertThat(labels(wslR.children())).containsExactly("a/b", "a0", "x");
            assertThat(labels(winR.children())).containsExactly("a\\b", "a0", "x");
        }

        @Test
        @DisplayName("환경 바로 아래 루트는 드라이브(글자순) → UNC(호스트 · 공유 세그먼트순) 순이다")
        void rootsOrderedByKind() {
            // given
            WorkHistory h = history(
                    path(WIN, "\\\\srv\\share\\y"),
                    path(WIN, "d:\\x"),
                    path(WIN, "\\\\a-b\\s\\q"),
                    path(WIN, "C:\\z"),
                    path(WIN, "\\\\a\\s\\q"));

            // when
            HistoryTree tree = project(h, List.of(), query(WIN));

            // then
            assertThat(labels(env(tree, WIN).children()))
                    .containsExactly("C:\\z", "D:\\x", "\\\\a\\s\\q", "\\\\a-b\\s\\q", "\\\\srv\\share\\y");
        }

        @Test
        @DisplayName("이름이 같은 환경은 Windows 가 앞선다")
        void windowsBeforeWslOfSameName() {
            // given
            ShellEnvironment wslWindows = new ShellEnvironment.Wsl("Windows");
            WorkHistory h = history(path(wslWindows, "/w"), path(WIN, "C:\\w"));

            // when
            HistoryTree tree = project(h, List.of(), query());

            // then
            assertThat(tree.environments()).extracting(EnvironmentNode::env).containsExactly(WIN, wslWindows);
        }

        @Test
        @DisplayName("환경은 탐지 순서대로, 목록에 없는 환경(지운 배포판)은 뒤에 이름순")
        void environmentOrder() {
            // given
            WorkHistory h = history(path(WSL_Z, "/z"), path(WSL_A, "/a"));

            // when
            HistoryTree tree = project(h, List.of(), query(WSL_B, WSL_A, WIN));

            // then
            assertThat(tree.environments()).extracting(EnvironmentNode::env).containsExactly(WSL_B, WSL_A, WIN, WSL_Z);
        }

        @Test
        @DisplayName("목록에 없는 환경이 여럿이면 이름순으로 뒤에 놓는다")
        void unlistedEnvironmentsByName() {
            // given
            WorkHistory h =
                    history(path(new ShellEnvironment.Wsl("Zed"), "/z"), path(new ShellEnvironment.Wsl("Old"), "/o"));

            // when
            HistoryTree tree = project(h, List.of(), query(WIN));

            // then
            assertThat(tree.environments())
                    .extracting(EnvironmentNode::env)
                    .containsExactly(WIN, new ShellEnvironment.Wsl("Old"), new ShellEnvironment.Wsl("Zed"));
        }

        @Test
        @DisplayName("목록에 없는 환경의 세션만 있어도 그 환경 노드가 생긴다")
        void unlistedEnvironmentFromSessionOnly() {
            // when
            HistoryTree tree = project(history(), List.of(sessionAtHome(1, WSL_Z)), query(WIN));

            // then
            assertThat(tree.environments()).extracting(EnvironmentNode::env).containsExactly(WIN, WSL_Z);
        }
    }

    @Nested
    class 숨김 {

        private WorkHistory hiddenAB() {
            WorkspacePath ab = path(WSL_U, "/a/b");
            return history(ab).hide(ab, true).value().orElseThrow();
        }

        @Test
        @DisplayName("showHidden=false 면 숨긴 항목만 있는 환경 노드에 자식이 없다")
        void hiddenOmitted() {
            // when
            HistoryTree tree = project(hiddenAB(), List.of(), new TreeQuery("", false, List.of(WSL_U)));

            // then
            assertThat(env(tree, WSL_U).children()).isEmpty();
        }

        @Test
        @DisplayName("showHidden=true 면 숨긴 항목이 보이고 visit.hidden 이 true 다")
        void hiddenShown() {
            // when
            HistoryTree tree = project(hiddenAB(), List.of(), new TreeQuery("", true, List.of(WSL_U)));

            // then
            TreeNode.Folder f = folder(env(tree, WSL_U).children().get(0));
            assertThat(f.label()).isEqualTo("/a/b");
            assertThat(f.visit()).hasValueSatisfying(v -> assertThat(v.hidden()).isTrue());
        }

        @Test
        @DisplayName("숨긴 항목에 세션이 있으면 노드는 생기지만 방문 정보는 없다")
        void hiddenWithSessionHasNodeWithoutVisit() {
            // given
            WorkspacePath ab = path(WSL_U, "/a/b");

            // when
            HistoryTree tree = project(hiddenAB(), List.of(session(1, ab)), new TreeQuery("", false, List.of(WSL_U)));

            // then
            TreeNode.Folder f = folder(env(tree, WSL_U).children().get(0));
            assertThat(f.path()).isEqualTo(ab);
            assertThat(f.visit()).isEmpty();
            assertThat(labels(f.children())).containsExactly("#1");
        }

        @Test
        @DisplayName("목록에 없는 환경은 항목이 모두 숨김이어도 빈 노드로 남는다")
        void unlistedEnvironmentWithOnlyHiddenEntriesStays() {
            // given
            WorkspacePath z = path(WSL_Z, "/z");
            WorkHistory h = history(z).hide(z, true).value().orElseThrow();

            // when
            HistoryTree tree = project(h, List.of(), new TreeQuery("", false, List.of(WIN)));

            // then
            assertThat(tree.environments()).extracting(EnvironmentNode::env).containsExactly(WIN, WSL_Z);
            assertThat(env(tree, WSL_Z).children()).isEmpty();
        }

        @Test
        @DisplayName("숨긴 항목은 방문하지 않은 것처럼 압축된다")
        void hiddenIntermediateCompresses() {
            // given
            WorkspacePath a = path(WSL_U, "/a");
            WorkHistory h =
                    history(a, path(WSL_U, "/a/b/c")).hide(a, true).value().orElseThrow();

            // when
            HistoryTree tree = project(h, List.of(), new TreeQuery("", false, List.of(WSL_U)));

            // then
            assertThat(labels(env(tree, WSL_U).children())).containsExactly("/a/b/c");
        }
    }
}
