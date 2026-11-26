package dev.tanager.adapter.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/** 이 모듈의 JSON 파일 저장소들이 함께 쓰는 쓰기 규칙 — 같은 들여쓰기 · LF 줄바꿈 · 임시 파일을 거친 원자적 교체. */
final class JsonFiles {
    private static final System.Logger LOG = System.getLogger(JsonFiles.class.getName());

    private JsonFiles() {}

    // 줄바꿈을 OS 와 상관없이 LF 로 고정해 WSL 과 Windows 가 같은 파일을 만들게 한다
    static ObjectWriter prettyWriter(JsonMapper mapper) {
        return mapper.writer().with(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")));
    }

    /** {@code <target>.tmp} 에 쓴 뒤 원자적으로 바꾼다. 실패하면 임시 파일을 지우고 던진다 — {@code target} 은 그대로다. */
    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw e;
        }
    }

    private static void deleteQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "임시 파일을 지우지 못했다: {0}", tmp);
        }
    }
}
