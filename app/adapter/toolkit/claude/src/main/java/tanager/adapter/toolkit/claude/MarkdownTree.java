package tanager.adapter.toolkit.claude;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 폴더 아래 {@code *.md} 를 하위 폴더까지 찾는다(커맨드 · 서브에이전트). 심볼릭 링크는 따라가되 같은 실제 폴더는 한 번만, 깊이 상한까지. */
final class MarkdownTree {

    /** 내려갈 하위 폴더 깊이 상한(서로를 가리키는 링크가 있어도 끝나게). */
    static final int MAX_DEPTH = 8;

    private static final String EXTENSION = ".md";

    /** 찾은 파일 하나. {@code path} 는 시작 폴더부터의 이름들(마지막은 확장자를 뺀 파일 이름). */
    @FunctionalInterface
    interface Visitor {
        void found(Location file, List<String> path);
    }

    private MarkdownTree() {}

    static void walk(Collector out, Location root, Visitor visitor) {
        if (out.enter(root)) {
            visit(out, root, List.of(), 0, visitor);
        }
    }

    private static void visit(Collector out, Location dir, List<String> prefix, int depth, Visitor visitor) {
        for (Location entry : out.list(dir)) {
            String name = entry.name();
            if (Files.isDirectory(entry.host())) {
                if (depth < MAX_DEPTH && out.enter(entry)) {
                    visit(out, entry, append(prefix, name), depth + 1, visitor);
                }
            } else if (name.endsWith(EXTENSION) && Files.isRegularFile(entry.host())) {
                visitor.found(entry, append(prefix, name.substring(0, name.length() - EXTENSION.length())));
            }
        }
    }

    private static List<String> append(List<String> prefix, String name) {
        List<String> out = new ArrayList<>(prefix);
        out.add(name);
        return List.copyOf(out);
    }
}
