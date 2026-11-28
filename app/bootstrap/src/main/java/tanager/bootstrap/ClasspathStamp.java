package tanager.bootstrap;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * AOT 캐시를 만들 때 jar 하나의 크기 · 내용 · 수정 시각(#221). 빌드({@code tanager.app-conventions})가 캐시 옆 기록 파일에 한 줄씩
 * 쓴다: {@code <sha256> <크기> <수정 시각 ISO-8601> <이름>}.
 *
 * @param name 기록 파일과 같은 폴더의 파일 이름(경로 구분자 없음)
 */
record ClasspathStamp(String name, long size, String sha256, Instant modified) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern FILE_NAME = Pattern.compile("[^/\\\\]+");

    ClasspathStamp {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(modified, "modified");
        if (!FILE_NAME.matcher(name).matches() || name.equals("..") || name.equals(".")) {
            throw new IllegalArgumentException("같은 폴더의 파일 이름이 아니다: " + name);
        }
        if (!SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 이 아니다: " + sha256);
        }
        if (size < 0) {
            throw new IllegalArgumentException("크기가 음수다: " + size);
        }
    }

    /** 한 줄을 읽는다. 빈 줄 · 주석({@code #}) · 형식이 틀린 줄은 비어 있다. */
    static Optional<ClasspathStamp> parse(String line) {
        String[] fields = line.strip().split(" ", 4);
        if (fields.length != 4 || fields[0].startsWith("#")) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new ClasspathStamp(fields[3], Long.parseLong(fields[1]), fields[0], Instant.parse(fields[2])));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** 크기는 같은데 수정 시각만 다르다 — 설치 · 압축 풀기가 시각을 밀었을 수 있다. 내용은 {@link #sha256} 으로 따로 확인한다. */
    boolean drifted(long actualSize, Instant actualModified) {
        return actualSize == size && !actualModified.equals(modified);
    }

    /** {@code digest} 가 이 기록의 내용과 같다. */
    boolean sameContent(byte[] digest) {
        return HexFormat.of().formatHex(digest).equals(sha256);
    }
}
