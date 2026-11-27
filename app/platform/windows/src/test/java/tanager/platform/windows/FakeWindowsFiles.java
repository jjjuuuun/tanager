package tanager.platform.windows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 경로 문자열마다 정해 둔 답을 돌려주는 수작업 Fake. 정하지 않은 디렉터리는 없다(false), 정하지 않은 파일은 없는 파일(빈 문자열)이다.
 * 받은 경로를 기록한다. 스레드 안전.
 */
final class FakeWindowsFiles implements WindowsFiles {
    private final Map<String, Optional<Boolean>> directories = new HashMap<>();
    private final Map<String, Optional<String>> texts = new HashMap<>();
    private final List<String> probed = new ArrayList<>();
    private final List<String> read = new ArrayList<>();
    private RuntimeException failure;

    synchronized FakeWindowsFiles directory(String path, Optional<Boolean> answer) {
        directories.put(path, answer);
        return this;
    }

    synchronized FakeWindowsFiles text(String path, Optional<String> content) {
        texts.put(path, content);
        return this;
    }

    /** {@code isDirectory} 가 이 예외를 던진다(구현 버그 흉내). */
    synchronized FakeWindowsFiles failWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    @Override
    public synchronized Optional<Boolean> isDirectory(String windowsPath) {
        probed.add(windowsPath);
        if (failure != null) {
            throw failure;
        }
        return directories.getOrDefault(windowsPath, Optional.of(false));
    }

    @Override
    public synchronized Optional<String> readText(String windowsPath) {
        read.add(windowsPath);
        return texts.getOrDefault(windowsPath, Optional.of(""));
    }

    /** {@code isDirectory} 로 받은 경로(받은 순서). */
    synchronized List<String> probed() {
        return List.copyOf(probed);
    }

    /** {@code readText} 로 받은 경로(받은 순서). */
    synchronized List<String> read() {
        return List.copyOf(read);
    }
}
