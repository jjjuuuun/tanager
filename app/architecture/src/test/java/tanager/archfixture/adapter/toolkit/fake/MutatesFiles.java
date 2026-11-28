package tanager.archfixture.adapter.toolkit.fake;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** 위반 픽스처: 도구함 어댑터가 여러 길로 파일을 쓰거나 지운다(FR-TK-07). */
public final class MutatesFiles {
    interface PathAction {
        void run(Path path) throws IOException;
    }

    PathAction deleteRef() {
        return Files::delete;
    }

    void move(Path from, Path to) throws IOException {
        Files.move(from, to);
    }

    void mkdirs(Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    Object outputStream(File file) throws FileNotFoundException {
        return new FileOutputStream(file);
    }

    Object channel(Path path) throws IOException {
        return FileChannel.open(path);
    }

    boolean legacyMkdirs(File dir) {
        return dir.mkdirs();
    }

    Object printer(String fileName) throws FileNotFoundException {
        return new PrintWriter(fileName);
    }

    void jackson(Path path) {
        JsonMapper.builder().build().writeValue(path, "x");
    }
}
