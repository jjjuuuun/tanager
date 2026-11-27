package tanager.domain.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class DirectoryTreeSearchProperties {

    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final ShellEnvironment WSL = new ShellEnvironment.Wsl("U");
    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    private static Arbitrary<List<String>> segments() {
        return Arbitraries.of(
                        "a",
                        "B",
                        "api",
                        "Api2",
                        "src",
                        "x y",
                        "proj",
                        "프로젝트",
                        Normalizer.normalize("프로", Normalizer.Form.NFD))
                .list()
                .ofMaxSize(4);
    }

    private static Arbitrary<WorkspacePath> paths() {
        Arbitrary<WorkspacePath> wsl = segments().map(l -> path(WSL, "/" + String.join("/", l)));
        Arbitrary<WorkspacePath> win = segments()
                .flatMap(l -> Arbitraries.of("C:\\", "d:\\").map(root -> path(WIN, root + String.join("\\", l))));
        return Arbitraries.oneOf(wsl, win);
    }

    private static Arbitrary<VisitedDirectory> entries() {
        Arbitrary<Optional<Alias>> alias =
                Arbitraries.of(Optional.empty(), Optional.of(new Alias("Src")), Optional.of(new Alias("프로젝트 A")));
        // 0 = 보통, 1 = 숨김, 2 = 고정 (고정과 숨김은 함께 설 수 없다)
        return Combinators.combine(paths(), alias, Arbitraries.integers().between(0, 2))
                .as((p, a, kind) -> new VisitedDirectory(p, T, T, 1, kind == 2, a, kind == 1));
    }

    @Provide
    Arbitrary<List<VisitedDirectory>> histories() {
        return entries().list().ofMaxSize(12).map(DirectoryTreeSearchProperties::uniqueByPath);
    }

    private static List<VisitedDirectory> uniqueByPath(List<VisitedDirectory> list) {
        List<VisitedDirectory> unique = new ArrayList<>();
        for (VisitedDirectory v : list) {
            if (unique.stream().noneMatch(u -> u.path().equals(v.path()))) {
                unique.add(v);
            }
        }
        return unique;
    }

    // 대소문자만 다른 부모(Work · work) 아래 형제가 여럿인 Windows 이력 — 검색 중 부모 표기가 비검색 트리와 같은지 보려고 좁게 만든다.
    @Provide
    Arbitrary<List<VisitedDirectory>> caseVariantHistories() {
        Arbitrary<VisitedDirectory> entry = Combinators.combine(
                        Arbitraries.of("Work", "work", "WORK"), Arbitraries.of("a", "api", "Api2", "b", "src"))
                .as((parent, child) -> new VisitedDirectory(
                        path(WIN, "C:\\" + parent + "\\" + child), T, T, 1, false, Optional.empty(), false));
        return entry.list().ofMaxSize(6).map(DirectoryTreeSearchProperties::uniqueByPath);
    }

    @Provide
    Arbitrary<String> queries() {
        return Arbitraries.of(
                "a", "A", "api", "API", "src", "x y", "/", "a/b", "/a", "\\", "d:", "프로", " api ", "zz", "");
    }

    // 구현의 고정 목록과 따로, 경로 · 별칭에 나오는 글자로 아무 검색어나 만든다(한글은 완성형 음절만 — 이어 붙여도 NFC 가 유지된다).
    @Provide
    Arbitrary<String> anyQueries() {
        Arbitrary<String> generated =
                Arbitraries.strings().withChars("aAbBpPiIsSrRcC2 /\\:프로젝트").ofMaxLength(5);
        return Arbitraries.oneOf(queries(), generated);
    }

    private static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    // 계약의 오라클: 구분자가 없으면 폴더 이름(루트는 경로) · 별칭, 있으면 전체 경로 · 별칭.
    // Windows 경로와 비교할 때만 검색어의 '/' 를 '\' 로 읽는다(별칭은 그대로).
    private static boolean matches(VisitedDirectory v, String text) {
        String needle = norm(text.strip());
        if (needle.isEmpty()) {
            return false;
        }
        boolean hasSeparator = needle.indexOf('/') >= 0 || needle.indexOf('\\') >= 0;
        WorkspacePath p = v.path();
        String target = hasSeparator || p.segments().isEmpty()
                ? p.path()
                : p.segments().get(p.segments().size() - 1);
        String pathNeedle = p.env() instanceof ShellEnvironment.Windows ? needle.replace('/', '\\') : needle;
        return norm(target).contains(pathNeedle)
                || v.alias().map(a -> norm(a.value()).contains(needle)).orElse(false);
    }

    private static List<TreeNode.Folder> folders(List<TreeNode> nodes) {
        List<TreeNode.Folder> out = new ArrayList<>();
        for (TreeNode n : nodes) {
            if (n instanceof TreeNode.Folder f) {
                out.add(f);
                out.addAll(folders(f.children()));
            }
        }
        return out;
    }

    private static List<TreeNode.Folder> allFolders(HistoryTree tree) {
        List<TreeNode.Folder> out = new ArrayList<>();
        tree.environments().forEach(e -> out.addAll(folders(e.children())));
        return out;
    }

    private static Set<WorkspacePath> matchedSet(HistoryTree tree) {
        Set<WorkspacePath> out = new HashSet<>();
        for (TreeNode.Folder f : allFolders(tree)) {
            if (f.matched()) {
                out.add(f.path());
            }
        }
        return out;
    }

    private static boolean hasSeparator(String s) {
        return s.indexOf('/') >= 0 || s.indexOf('\\') >= 0;
    }

    private static HistoryTree project(List<VisitedDirectory> entries, String text, boolean showHidden) {
        return DirectoryTree.project(
                WorkHistory.restore(entries, HistoryPolicy.DEFAULT),
                List.of(),
                new TreeQuery(text, showHidden, List.of(WSL, WIN)));
    }

    @Property
    @Label("matched 인 폴더는 보이는 방문 중 오라클이 맞다고 한 경로와 정확히 같다")
    void matchedFoldersEqualOracle(
            @ForAll("histories") List<VisitedDirectory> entries,
            @ForAll("queries") String text,
            @ForAll boolean showHidden) {
        // when
        List<WorkspacePath> matched = allFolders(project(entries, text, showHidden)).stream()
                .filter(TreeNode.Folder::matched)
                .map(TreeNode.Folder::path)
                .toList();

        // then
        List<WorkspacePath> expected = entries.stream()
                .filter(v -> showHidden || !v.hidden())
                .filter(v -> matches(v, text))
                .map(VisitedDirectory::path)
                .toList();
        assertThat(matched).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Property
    @Label("거른 트리의 모든 폴더는 맞았거나 맞은 폴더를 아래에 가진다")
    void everyKeptFolderIsMatchedOrAncestorOfMatch(
            @ForAll("histories") List<VisitedDirectory> entries, @ForAll("queries") String text) {
        // when
        HistoryTree tree = project(entries, text, false);

        // then
        if (text.isBlank()) {
            return;
        }
        for (TreeNode.Folder f : allFolders(tree)) {
            boolean keep = f.matched() || folders(f.children()).stream().anyMatch(TreeNode.Folder::matched);
            assertThat(keep).as("폴더 %s", f.label()).isTrue();
        }
    }

    @Property
    @Label("고정됨 섹션은 고정 항목 중 오라클이 맞다고 한 것만, 검색어가 있으면 모두 matched 로 담는다")
    void pinnedSectionEqualsOracle(
            @ForAll("histories") List<VisitedDirectory> entries, @ForAll("queries") String text) {
        // when
        HistoryTree tree = project(entries, text, false);

        // then
        List<WorkspacePath> expected = entries.stream()
                .filter(VisitedDirectory::pinned)
                .filter(v -> text.isBlank() || matches(v, text))
                .map(v -> v.path())
                .toList();
        assertThat(tree.pinned()).extracting(p -> p.entry().path()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(tree.pinned())
                .extracting(PinnedEntry::matched)
                .hasSize(expected.size())
                .allMatch(matched -> matched == !text.isBlank());
    }

    @Property
    @Label("공백뿐인 검색어는 빈 검색어와 같은 트리를 낸다")
    void blankEqualsEmpty(@ForAll("histories") List<VisitedDirectory> entries, @ForAll boolean showHidden) {
        // when / then
        assertThat(project(entries, " \t ", showHidden)).isEqualTo(project(entries, "", showHidden));
    }

    @Property
    @Label("거른 트리의 방문 노드는 거르기 전 트리에도 있고 압축 규칙을 여전히 지킨다")
    void filteredTreeIsSubsetAndStaysCompressed(
            @ForAll("histories") List<VisitedDirectory> entries, @ForAll("queries") String text) {
        // when
        HistoryTree filtered = project(entries, text, false);
        HistoryTree full = project(entries, "", false);

        // then
        List<WorkspacePath> fullVisits = allFolders(full).stream()
                .filter(f -> f.visit().isPresent())
                .map(TreeNode.Folder::path)
                .toList();
        for (TreeNode.Folder f : allFolders(filtered)) {
            if (f.visit().isPresent()) {
                assertThat(fullVisits).contains(f.path());
            }
            boolean isRoot = f.path().parent().isEmpty();
            if (f.visit().isEmpty() && !isRoot) {
                assertThat(f.children()).as("압축되지 않은 중간 노드 %s", f.label()).hasSizeGreaterThan(1);
            }
        }
    }

    @Property
    @Label("대문자 검색어와 소문자 검색어는 같은 트리를 낸다")
    void caseOfQueryDoesNotMatter(
            @ForAll("histories") List<VisitedDirectory> entries,
            @ForAll("anyQueries") String text,
            @ForAll boolean showHidden) {
        // when
        HistoryTree upper = project(entries, text.toUpperCase(Locale.ROOT), showHidden);
        HistoryTree lower = project(entries, text.toLowerCase(Locale.ROOT), showHidden);

        // then
        assertThat(upper).isEqualTo(lower);
    }

    @Property
    @Label("NFD 검색어와 NFC 검색어는 같은 트리를 낸다")
    void normalizationOfQueryDoesNotMatter(
            @ForAll("histories") List<VisitedDirectory> entries,
            @ForAll("anyQueries") String text,
            @ForAll boolean showHidden) {
        // when
        HistoryTree nfd = project(entries, Normalizer.normalize(text, Normalizer.Form.NFD), showHidden);
        HistoryTree nfc = project(entries, Normalizer.normalize(text, Normalizer.Form.NFC), showHidden);

        // then
        assertThat(nfd).isEqualTo(nfc);
    }

    @Property
    @Label("구분자 유무가 같으면 검색어를 늘려도 matched 집합은 줄어들기만 한다")
    void longerQueryNarrowsMatches(
            @ForAll("histories") List<VisitedDirectory> entries,
            @ForAll("anyQueries") String text,
            @ForAll("anyQueries") String prefix,
            @ForAll("anyQueries") String suffix,
            @ForAll boolean showHidden) {
        // given
        String longer = prefix + text + suffix;
        Assume.that(!text.isBlank() && hasSeparator(text) == hasSeparator(longer));

        // when
        Set<WorkspacePath> narrow = matchedSet(project(entries, longer, showHidden));
        Set<WorkspacePath> wide = matchedSet(project(entries, text, showHidden));

        // then
        assertThat(wide).containsAll(narrow);
    }

    @Property
    @Label("matched 인 폴더는 방문 기록이 있다")
    void matchedFolderHasVisit(
            @ForAll("histories") List<VisitedDirectory> entries,
            @ForAll("anyQueries") String text,
            @ForAll boolean showHidden) {
        // when
        List<TreeNode.Folder> all = allFolders(project(entries, text, showHidden));

        // then
        for (TreeNode.Folder f : all) {
            if (f.matched()) {
                assertThat(f.visit()).as("폴더 %s", f.label()).isPresent();
            }
        }
    }

    @Property
    @Label("거른 트리의 폴더 표기는 거르지 않은 트리의 같은 폴더 표기와 같다")
    void filteredSpellingEqualsUnfiltered(
            @ForAll("caseVariantHistories") List<VisitedDirectory> entries,
            @ForAll("anyQueries") String text,
            @ForAll boolean showHidden) {
        // given
        Map<WorkspacePath, String> unfiltered = new HashMap<>();
        for (TreeNode.Folder f : allFolders(project(entries, "", showHidden))) {
            unfiltered.put(f.path(), f.path().path());
        }

        // when
        List<TreeNode.Folder> filtered = allFolders(project(entries, text, showHidden));

        // then
        for (TreeNode.Folder f : filtered) {
            assertThat(unfiltered)
                    .as("폴더 %s", f.label())
                    .containsEntry(f.path(), f.path().path());
        }
    }
}
