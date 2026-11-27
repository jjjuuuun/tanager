package tanager.adapter.persistence;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import tanager.application.port.out.ConfigWatch;
import tanager.application.port.out.Subscription;

/**
 * config.json 이 든 폴더를 {@link WatchService} 로 보고, 그 파일의 만들기 · 수정(이름 바꿔 넣기는 만들기로 온다)을 모아 한 번 알린다
 * (FR-CFG-03, ADR-0009 개정). 편집기 한 번의 저장은 이벤트 여러 개(잘라내기 · 쓰기 · 이름 바꾸기)가 되므로, 마지막 이벤트 뒤 {@link #QUIET}
 * 동안 조용하면 그때 콜백을 부른다. 구독마다 가상 스레드 하나가 돈다.
 */
public final class WatchServiceConfigWatch implements ConfigWatch {
    /** 이벤트를 모으는 시간. */
    public static final Duration QUIET = Duration.ofMillis(300);

    private static final System.Logger LOG = System.getLogger(WatchServiceConfigWatch.class.getName());

    private final Path file;

    /** {@code file} 은 보통 {@code PlatformDirs.appData()/config.json}. 만들 때 파일을 건드리지 않는다. */
    public WatchServiceConfigWatch(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
    }

    @Override
    public Subscription watch(Runnable changed) {
        Objects.requireNonNull(changed, "changed");
        Path directory = file.getParent();
        WatchService service;
        try {
            // 앱 데이터 폴더는 첫 실행에 아직 없을 수 있다 — 우리 폴더라 만들어도 된다
            Files.createDirectories(directory);
            service = directory.getFileSystem().newWatchService();
        } catch (IOException | UnsupportedOperationException e) {
            LOG.log(System.Logger.Level.WARNING, "설정 파일을 감시하지 못한다 — 바꾸면 다시 시작해야 반영된다: " + file, e);
            return () -> {};
        }
        try {
            directory.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
            closeQuietly(service);
            LOG.log(System.Logger.Level.WARNING, "설정 파일을 감시하지 못한다 — 바꾸면 다시 시작해야 반영된다: " + file, e);
            return () -> {};
        }
        Thread.ofVirtual().name("tanager-config-watch").start(() -> loop(service, changed));
        return () -> closeQuietly(service);
    }

    private void loop(WatchService service, Runnable changed) {
        try {
            while (true) {
                WatchKey key = service.take();
                if (!drain(key)) {
                    continue;
                }
                settle(service);
                notify(changed);
            }
        } catch (ClosedWatchServiceException e) {
            // 구독을 끊었다
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (DirectoryGone e) {
            LOG.log(System.Logger.Level.WARNING, "설정 폴더가 사라져 감시를 멈춘다: {0}", file.getParent());
        }
    }

    // 마지막 관련 이벤트 뒤 QUIET 동안 조용해질 때까지 기다린다. 다른 파일의 이벤트는 기한을 늘리지 않는다
    private void settle(WatchService service) throws InterruptedException {
        long deadline = System.nanoTime() + QUIET.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            WatchKey more = service.poll(remaining, TimeUnit.NANOSECONDS);
            if (more == null) {
                return;
            }
            if (drain(more)) {
                deadline = System.nanoTime() + QUIET.toNanos();
            }
        }
    }

    /** 키의 이벤트를 비우고 다시 받을 수 있게 한다. config.json 의 이벤트(또는 넘침)가 있었으면 {@code true}. */
    private boolean drain(WatchKey key) {
        boolean relevant = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            // OVERFLOW 는 놓친 이벤트가 있다는 뜻 — 다시 읽어도 해가 없다
            if (event.kind() == StandardWatchEventKinds.OVERFLOW
                    || file.getFileName().equals(event.context())) {
                relevant = true;
            }
        }
        if (!key.reset()) {
            throw new DirectoryGone();
        }
        return relevant;
    }

    private void notify(Runnable changed) {
        try {
            changed.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "설정 변경 콜백 실패 — 감시는 계속한다", e);
        }
    }

    private static void closeQuietly(WatchService service) {
        try {
            service.close();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "설정 감시를 닫지 못했다", e);
        }
    }

    /** 감시하던 폴더가 사라져 키가 무효가 됐다. */
    private static final class DirectoryGone extends RuntimeException {
        DirectoryGone() {
            super(null, null, false, false);
        }
    }
}
