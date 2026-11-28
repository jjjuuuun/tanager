package tanager.adapter.toolkit.claude;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** 대화 기록 파일을 읽기 전용으로 연다. 테스트가 읽힌 바이트를 세는 가짜 스트림을 넣는 자리다. */
@FunctionalInterface
interface TranscriptOpener {
    InputStream open(Path file) throws IOException;
}
