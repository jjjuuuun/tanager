package tanager.platform.windows;

import java.util.Optional;

/** Windows 쪽 파일 경로(드라이브 · {@code \\wsl.localhost} UNC)로 파일시스템을 본다. 블로킹, 예외를 던지지 않는다. */
interface WindowsFiles {

    /** 디렉터리면 true, 없거나 디렉터리가 아니면 false, 모르면(접근 불가 · 경로로 쓸 수 없음) empty. */
    Optional<Boolean> isDirectory(String windowsPath);

    /** UTF-8 로 읽은 내용. 파일이 없으면 빈 문자열, 읽지 못하면 empty. */
    Optional<String> readText(String windowsPath);
}
