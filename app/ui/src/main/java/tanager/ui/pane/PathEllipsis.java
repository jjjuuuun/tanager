package tanager.ui.pane;

import java.util.ArrayList;
import java.util.List;

/**
 * 머리줄 경로를 폭(코드 포인트 수)에 맞게 가운데를 줄인다(#82). 루트와 뒤쪽 폴더를 남긴다 — 사용자가 알아보는 것은 경로의 끝이다.
 *
 * <ul>
 *   <li>루트: Windows 는 드라이브({@code C:}), WSL 의 {@code /mnt/<드라이브>} 는 그 둘, 그 밖은 첫 폴더.
 *   <li>루트 + {@code …} + 뒤쪽 폴더를 폭이 허락하는 만큼 붙인다.
 *   <li>루트와 마지막 폴더가 함께 들어가지 않으면 {@code …/마지막}, 그마저 넘치면 마지막 폴더의 끝을 {@code …} 로 자른다.
 * </ul>
 */
final class PathEllipsis {
    static final String ELLIPSIS = "…";

    private PathEllipsis() {}

    /** {@code path} 를 {@code width} 코드 포인트 이하로 줄인 글자. */
    static String fit(String path, int width) {
        if (width < 1) {
            throw new IllegalArgumentException("폭은 1 이상이어야 한다: " + width);
        }
        if (length(path) <= width) {
            return path;
        }
        String separator = path.indexOf('\\') >= 0 && path.indexOf('/') < 0 ? "\\" : "/";
        List<String> segments = segments(path, separator);
        if (segments.isEmpty()) {
            return truncate(path, width);
        }
        int rootCount = rootCount(path, segments, separator);
        String last = segments.getLast();
        if (segments.size() > rootCount) {
            String root = root(path, segments, rootCount, separator);
            String best = "";
            String tail = "";
            for (int i = segments.size() - 1; i >= rootCount; i--) {
                tail = segments.get(i) + (tail.isEmpty() ? "" : separator + tail);
                String candidate = root + separator + ELLIPSIS + separator + tail;
                if (length(candidate) > width) {
                    break;
                }
                best = candidate;
            }
            if (!best.isEmpty()) {
                return best;
            }
        }
        String lastOnly = ELLIPSIS + separator + last;
        if (length(lastOnly) <= width) {
            return lastOnly;
        }
        return truncate(last, width);
    }

    private static List<String> segments(String path, String separator) {
        List<String> segments = new ArrayList<>();
        for (String part : path.split(java.util.regex.Pattern.quote(separator))) {
            if (!part.isEmpty()) {
                segments.add(part);
            }
        }
        return segments;
    }

    // /mnt/<드라이브> 는 둘을 루트로 본다 — WSL 에서 Windows 드라이브를 가리키는 가장 큰 단서다
    private static int rootCount(String path, List<String> segments, String separator) {
        boolean mount = separator.equals("/")
                && path.startsWith("/")
                && segments.size() > 2
                && segments.getFirst().equals("mnt");
        return mount ? 2 : 1;
    }

    private static String root(String path, List<String> segments, int rootCount, String separator) {
        String leading = path.startsWith(separator) ? separator : "";
        return leading + String.join(separator, segments.subList(0, rootCount));
    }

    private static String truncate(String text, int width) {
        if (width == 1) {
            return ELLIPSIS;
        }
        int end = text.offsetByCodePoints(0, width - 1);
        return text.substring(0, end) + ELLIPSIS;
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
