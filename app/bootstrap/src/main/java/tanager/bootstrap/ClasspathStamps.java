package tanager.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

/**
 * 설치가 밀어 놓은 jar 수정 시각을 AOT 캐시를 만들 때 값으로 되돌린다(#221). JVM 은 클래스패스 jar 의 수정 시각이 캐시를
 * 만들 때와 다르면 캐시를 통째로 버린다. MSI · zip 은 시각을 현지 시간 2초 단위로 담아 빌드(CI 는 UTC)와 다른 시간대 · 홀수 초에서 늘
 * 달라진다 — 그래서 첫 실행에서 내용(sha256)이 같은 jar 만 시각을 되돌려 다음 실행부터 캐시가 쓰이게 한다.
 */
final class ClasspathStamps {
    private static final System.Logger LOG = System.getLogger(ClasspathStamps.class.getName());

    private ClasspathStamps() {}

    /**
     * {@code manifest} 에 적힌 jar 중 내용이 같고 시각만 밀린 것을 되돌린다. 실패는 로그만 남긴다 — 캐시를 못 쓸 뿐 앱은 그대로 돈다.
     *
     * @return 되돌린 jar 수
     */
    static int restore(Path manifest) {
        List<String> lines;
        try {
            lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return 0;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "AOT 캐시 jar 기록을 읽지 못했다: " + manifest, e);
            return 0;
        }
        Path folder = manifest.toAbsolutePath().getParent();
        int restored = 0;
        for (String line : lines) {
            Optional<ClasspathStamp> stamp = ClasspathStamp.parse(line);
            if (stamp.isPresent() && restore(folder.resolve(stamp.get().name()), stamp.get())) {
                restored++;
            }
        }
        return restored;
    }

    private static boolean restore(Path jar, ClasspathStamp stamp) {
        try {
            BasicFileAttributes actual = Files.readAttributes(jar, BasicFileAttributes.class);
            if (!stamp.drifted(actual.size(), actual.lastModifiedTime().toInstant())
                    || !stamp.sameContent(sha256(jar))) {
                return false;
            }
            Files.setLastModifiedTime(jar, FileTime.from(stamp.modified()));
            return true;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "jar 수정 시각을 되돌리지 못했다: " + jar, e);
            return false;
        }
    }

    private static byte[] sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 은 모든 JDK 에 있다", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }
}
