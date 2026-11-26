package dev.tanager.domain.history;

import static dev.tanager.domain.history.DirectoryTreeTest.env;
import static dev.tanager.domain.history.DirectoryTreeTest.folder;
import static dev.tanager.domain.history.DirectoryTreeTest.history;
import static dev.tanager.domain.history.DirectoryTreeTest.labels;
import static dev.tanager.domain.history.DirectoryTreeTest.path;
import static dev.tanager.domain.history.DirectoryTreeTest.project;
import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DirectoryTreePinnedTest {

    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment WSL_U = new ShellEnvironment.Wsl("U");

    private static final TreeQuery ALL = new TreeQuery("", false, List.of(WIN, WSL_U));

    private static WorkHistory pin(WorkHistory h, WorkspacePath p) {
        return h.pin(p, true).value().orElseThrow();
    }

    private static WorkHistory alias(WorkHistory h, WorkspacePath p, String alias) {
        return h.alias(p, Optional.of(alias)).value().orElseThrow();
    }

    private static List<String> pinnedLabels(HistoryTree tree) {
        return tree.pinned().stream().map(PinnedEntry::label).toList();
    }

    @Test
    @DisplayName("고정 항목은 환경을 가로질러 평평하게 라벨(별칭 또는 마지막 세그먼트) 순으로 나오고 환경 트리에도 있다")
    void pinnedAreFlatSortedByLabelAndAlsoInTree() {
        // given
        WorkspacePath tanager = path(WSL_U, "/mnt/c/Users/user/Desktop/Tanager");
        WorkspacePath infra = path(WIN, "C:\\work\\infra");
        WorkHistory h = alias(pin(pin(history(infra, tanager), infra), tanager), infra, "인프라");

        // when
        HistoryTree tree = project(h, List.of(), ALL);

        // then
        assertThat(pinnedLabels(tree)).containsExactly("Tanager", "인프라");
        assertThat(tree.pinned()).extracting(p -> p.entry().path()).containsExactly(tanager, infra);
        assertThat(labels(env(tree, WSL_U).children())).containsExactly("/mnt/c/Users/user/Desktop/Tanager");
        assertThat(labels(env(tree, WIN).children())).containsExactly("C:\\work\\infra");
    }

    @Test
    @DisplayName("고정하지 않은 항목은 고정됨 섹션에 없다")
    void unpinnedAreNotListed() {
        // given
        WorkspacePath a = path(WSL_U, "/a");
        WorkspacePath b = path(WSL_U, "/b");

        // when
        HistoryTree tree = project(pin(history(a, b), b), List.of(), ALL);

        // then
        assertThat(tree.pinned()).extracting(p -> p.entry().path()).containsExactly(b);
    }

    @Test
    @DisplayName("고정 항목이 없으면 고정됨 섹션은 비어 있다")
    void noPinnedMeansEmptySection() {
        // when
        HistoryTree tree = project(history(path(WSL_U, "/a")), List.of(), ALL);

        // then
        assertThat(tree.pinned()).isEmpty();
    }

    @Test
    @DisplayName("루트를 고정하면 라벨이 경로 전체다")
    void rootLabelIsFullPath() {
        // given
        WorkspacePath wslRoot = path(WSL_U, "/");
        WorkspacePath drive = path(WIN, "C:\\");

        // when
        HistoryTree tree = project(pin(pin(history(wslRoot, drive), wslRoot), drive), List.of(), ALL);

        // then
        assertThat(pinnedLabels(tree)).containsExactlyInAnyOrder("/", "C:\\");
    }

    @Test
    @DisplayName("라벨 순서는 대소문자를 무시한다")
    void labelOrderIgnoresCase() {
        // given
        WorkspacePath a = path(WSL_U, "/x/beta");
        WorkspacePath b = path(WSL_U, "/x/Alpha");
        WorkspacePath c = path(WSL_U, "/x/gamma");

        // when
        HistoryTree tree = project(pin(pin(pin(history(a, b, c), a), b), c), List.of(), ALL);

        // then
        assertThat(pinnedLabels(tree)).containsExactly("Alpha", "beta", "gamma");
    }

    @Test
    @DisplayName("라벨이 같으면 경로 순이고 입력 순서와 상관없다")
    void sameLabelFallsBackToPathOrder() {
        // given
        WorkspacePath first = path(WSL_U, "/a/x");
        WorkspacePath second = path(WSL_U, "/b/x");

        // when
        HistoryTree forward = project(pin(pin(history(first, second), first), second), List.of(), ALL);
        HistoryTree backward = project(pin(pin(history(second, first), second), first), List.of(), ALL);

        // then
        assertThat(forward.pinned()).extracting(p -> p.entry().path()).containsExactly(first, second);
        assertThat(backward.pinned()).isEqualTo(forward.pinned());
    }

    @Test
    @DisplayName("여러 배포판이 같은 경로를 고정하면 환경 이름순이고 입력 순서와 상관없다")
    void samePathInSeveralDistrosOrderedByEnvironment() {
        // given — 이력 Map 의 순회 순서에 기대지 않게 배포판을 여럿 둔다
        List<String> names = List.of("Ubuntu", "alpine", "Debian", "kali", "Fedora", "Arch", "openSUSE", "Mint");
        List<WorkspacePath> forward = new ArrayList<>();
        for (String name : names) {
            forward.add(path(new ShellEnvironment.Wsl(name), "/home/me/proj"));
        }
        List<WorkspacePath> backward = new ArrayList<>(forward);
        Collections.reverse(backward);

        // when
        HistoryTree a = project(pinAll(forward), List.of(), ALL);
        HistoryTree b = project(pinAll(backward), List.of(), ALL);

        // then
        assertThat(a.pinned())
                .extracting(p -> ((ShellEnvironment.Wsl) p.entry().path().env()).distro())
                .containsExactly("alpine", "Arch", "Debian", "Fedora", "kali", "Mint", "openSUSE", "Ubuntu");
        assertThat(b.pinned()).isEqualTo(a.pinned());
    }

    @Test
    @DisplayName("NFD 라벨도 NFC 로 맞춰 정렬한다")
    void nfdLabelSortedAsNfc() {
        // given
        WorkspacePath sky = path(WSL_U, "/x/" + Normalizer.normalize("하늘", Normalizer.Form.NFD));
        WorkspacePath river = path(WSL_U, "/x/" + Normalizer.normalize("가람", Normalizer.Form.NFC));

        // when
        HistoryTree tree = project(pinAll(List.of(sky, river)), List.of(), ALL);

        // then
        assertThat(tree.pinned()).extracting(p -> p.entry().path()).containsExactly(river, sky);
    }

    private static WorkHistory pinAll(List<WorkspacePath> paths) {
        WorkHistory h = history(paths.toArray(WorkspacePath[]::new));
        for (WorkspacePath p : paths) {
            h = pin(h, p);
        }
        return h;
    }

    @Test
    @DisplayName("검색어가 없으면 고정 항목은 matched 가 아니다")
    void pinnedNotMatchedWithoutQuery() {
        // given
        WorkspacePath a = path(WSL_U, "/a");

        // when
        HistoryTree tree = project(pin(history(a), a), List.of(), ALL);

        // then
        assertThat(tree.pinned()).extracting(PinnedEntry::matched).containsExactly(false);
    }

    @Test
    @DisplayName("검색하면 고정됨 섹션도 같은 규칙으로 거르고 남은 항목은 matched 다")
    void searchFiltersPinnedSection() {
        // given
        WorkspacePath tanager = path(WSL_U, "/mnt/c/Desktop/Tanager");
        WorkspacePath infra = path(WIN, "C:\\work\\infra");
        WorkHistory h = alias(pin(pin(history(tanager, infra), tanager), infra), infra, "인프라");

        // when
        HistoryTree byAlias = project(h, List.of(), new TreeQuery("인프", false, List.of(WIN, WSL_U)));
        HistoryTree byName = project(h, List.of(), new TreeQuery("TAN", false, List.of(WIN, WSL_U)));

        // then
        assertThat(pinnedLabels(byAlias)).containsExactly("인프라");
        assertThat(byAlias.pinned()).extracting(PinnedEntry::matched).containsExactly(true);
        assertThat(pinnedLabels(byName)).containsExactly("Tanager");
    }

    @Test
    @DisplayName("고정 항목은 별칭이 있어도 폴더 이름으로 검색된다")
    void pinnedFoundByFolderNameEvenWithAlias() {
        // given
        WorkspacePath infra = path(WIN, "C:\\work\\infra");
        WorkHistory h = alias(pin(history(infra), infra), infra, "인프라");

        // when
        HistoryTree tree = project(h, List.of(), new TreeQuery("infra", false, List.of(WIN)));

        // then
        assertThat(pinnedLabels(tree)).containsExactly("인프라");
    }

    @Test
    @DisplayName("구분자가 있는 검색어는 고정 항목의 전체 경로에서 찾는다")
    void separatorSearchUsesFullPathForPinned() {
        // given
        WorkspacePath inProj = path(WSL_U, "/home/me/proj/api");
        WorkspacePath other = path(WSL_U, "/srv/api");
        WorkHistory h = pin(pin(history(inProj, other), inProj), other);

        // when
        HistoryTree tree = project(h, List.of(), new TreeQuery("me/proj", false, List.of(WSL_U)));

        // then
        assertThat(tree.pinned()).extracting(p -> p.entry().path()).containsExactly(inProj);
    }

    @Test
    @DisplayName("검색에 맞지 않는 고정 항목도 환경 트리의 거르기와 따로 판단되고 일치하는 것만 남는다")
    void pinnedNotMatchingIsDropped() {
        // given
        WorkspacePath a = path(WSL_U, "/a/api");
        WorkspacePath b = path(WSL_U, "/b/web");

        // when
        HistoryTree tree =
                project(pin(pin(history(a, b), a), b), List.of(), new TreeQuery("web", false, List.of(WSL_U)));

        // then
        assertThat(pinnedLabels(tree)).containsExactly("web");
        assertThat(folder(env(tree, WSL_U).children().get(0)).path()).isEqualTo(b);
    }
}
