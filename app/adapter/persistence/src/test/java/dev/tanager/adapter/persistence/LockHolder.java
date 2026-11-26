package dev.tanager.adapter.persistence;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 다른 프로세스가 state.lock 을 쥔 상황을 만드는 자식 JVM. {@code main} 은 인자로 받은 잠금 파일을 잡고 {@link #READY} 를 한 줄 출력한
 * 뒤, 표준입력이 닫힐 때까지 쥐고 있다. JDK 클래스만 써서 테스트 클래스 폴더 하나로 돈다.
 */
public final class LockHolder {
    static final String READY = "LOCKED";
    private static final long TIMEOUT_SECONDS = 30;

    private LockHolder() {}

    public static void main(String[] args) throws IOException {
        try (FileChannel channel =
                        FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock _ = channel.lock()) {
            System.out.println(READY);
            System.out.flush();
            while (System.in.read() != -1) {
                // 부모가 표준입력을 닫을 때까지 잠금을 쥔다
            }
        }
    }

    /** 자식 JVM 을 띄우고 잠금을 잡았다는 신호를 기다린다. 신호 없이 끝나거나 시간이 지나면 정리하고 던진다. */
    static Process start(Path lockFile) throws Exception {
        String java = ProcessHandle.current()
                .info()
                .command()
                .orElseGet(() ->
                        Path.of(System.getProperty("java.home"), "bin", "java").toString());
        Process process = new ProcessBuilder(java, "-cp", classesDir(), LockHolder.class.getName(), lockFile.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        BufferedReader out =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line;
        try {
            line = CompletableFuture.supplyAsync(() -> readLine(out)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            stop(process);
            throw e;
        }
        if (!READY.equals(line)) {
            stop(process);
            throw new IllegalStateException("자식 JVM 이 잠금을 잡지 못했다: " + line);
        }
        return process;
    }

    /** 표준입력을 닫아 끝내고, 끝나지 않으면 강제로 끝낸다. */
    static void stop(Process process) throws InterruptedException {
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // 이미 끝났다
        }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor();
        }
    }

    private static String classesDir() throws URISyntaxException {
        return Path.of(LockHolder.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
    }

    private static String readLine(BufferedReader reader) {
        try {
            return reader.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
