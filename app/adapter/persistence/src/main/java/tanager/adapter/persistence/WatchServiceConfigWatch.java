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
import java.util.Optional;
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
        Optional<WatchService> opened = open();
        if (opened.isEmpty()) {
            return () -> {};
        }
        WatchService service = opened.get();
        Thread.ofVirtual().name("tanager-config-watch").start(() -> loop(service, changed));
        return () -> closeQuietly(service);
    }

    private Optional<WatchService> open() {
        Path directory = file.getParent();
        WatchService service = null;
        try {
            // 앱 데이터 폴더는 첫 실행에 아직 없을 수 있다 — 우리 폴더라 만들어도 된다
            Files.createDirectories(directory);
            service = directory.getFileSystem().newWatchService();
            directory.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            return Optional.of(service);
        } catch (IOException | UnsupportedOperationException e) {
            Optional.ofNullable(service).ifPresent(WatchServiceConfigWatch::closeQuietly);
            LOG.log(System.Logger.Level.WARNING, "설정 파일을 감시하지 못한다 — 바꾸면 다시 시작해야 반영된다: " + file, e);
            return Optional.empty();
        }
    }

    private void loop(WatchService service, Runnable changed) {
        try {
            while (true) {
                Drained first = drain(service.take());
                if (first == Drained.CONFIG) {
                    first = settle(service);
                }
                if (first == Drained.CONFIG) {
                    notify(changed);
                }
                if (first == Drained.GONE) {
                    LOG.log(System.Logger.Level.WARNING, "설정 폴더가 사라져 감시를 멈춘다: {0}", file.getParent());
                    return;
                }
            }
        } catch (ClosedWatchServiceException e) {
            // 구독을 끊었다
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 마지막 관련 이벤트 뒤 QUIET 동안 조용해질 때까지 기다린다. 다른 파일의 이벤트는 기한을 늘리지 않는다
    // 폴더가 사라지면 기다리지 않고 GONE 을 돌려준다
    private Drained settle(WatchService service) throws InterruptedException {
        long deadline = System.nanoTime() + QUIET.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return Drained.CONFIG;
            }
            WatchKey more = service.poll(remaining, TimeUnit.NANOSECONDS);
            if (more == null) {
                return Drained.CONFIG;
            }
            switch (drain(more)) {
                case CONFIG -> deadline = System.nanoTime() + QUIET.toNanos();
                case OTHER -> {
                    // 다른 파일 — 기한을 늘리지 않는다
                }
                case GONE -> {
                    return Drained.GONE;
                }
            }
        }
    }

    /** 키의 이벤트를 비우고 다시 받을 수 있게 한다. */
    private Drained drain(WatchKey key) {
        boolean relevant = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            // OVERFLOW 는 놓친 이벤트가 있다는 뜻 — 다시 읽어도 해가 없다
            if (event.kind() == StandardWatchEventKinds.OVERFLOW
                    || file.getFileName().equals(event.context())) {
                relevant = true;
            }
        }
        if (!key.reset()) {
            return Drained.GONE;
        }
        return relevant ? Drained.CONFIG : Drained.OTHER;
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

    /** 키 하나를 비운 결과. */
    private enum Drained {
        /** config.json 의 이벤트(또는 놓친 이벤트가 있다는 넘침)가 있었다. */
        CONFIG,
        /** 같은 폴더의 다른 파일 이벤트뿐이었다. */
        OTHER,
        /** 감시하던 폴더가 사라져 키가 무효가 됐다. */
        GONE
    }
}
