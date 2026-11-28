package tanager.archfixture.adapter.toolkit.fake;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import tools.jackson.databind.json.JsonMapper;

/** 허용 픽스처: 도구함 어댑터가 읽기만 한다 — 메모리 안 문자열 쓰기는 파일 쓰기가 아니다. */
public final class ReadsSettings {
    String read(Path path) throws IOException {
        return Files.readString(path);
    }

    InputStream open(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    DirectoryStream<Path> list(Path dir) throws IOException {
        return Files.newDirectoryStream(dir);
    }

    Instant modified(Path path) throws IOException {
        return Files.getLastModifiedTime(path).toInstant();
    }

    boolean exists(File file) {
        return file.exists() && Files.isRegularFile(file.toPath());
    }

    String inMemory() {
        StringWriter out = new StringWriter();
        new PrintWriter(out).print("x");
        return out + JsonMapper.builder().build().writeValueAsString("y");
    }
}
