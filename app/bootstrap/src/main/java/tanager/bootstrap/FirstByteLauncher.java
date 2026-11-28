package tanager.bootstrap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import tanager.application.port.out.LaunchCommand;
import tanager.application.port.out.LaunchError;
import tanager.application.port.out.PtyLauncher;
import tanager.application.port.out.Subscription;
import tanager.application.port.out.TerminalSize;
import tanager.application.port.out.TerminalStream;
import tanager.domain.shared.Result;

/**
 * 어느 셸이든 처음 나온 바이트를 한 번 알리는 {@link PtyLauncher} 장식(시작 시간 로그, #221). 받은 뒤로는 구독을 떼고 새 셸에도
 * 붙이지 않는다 — 출력 경로에 남는 비용이 없다.
 */
final class FirstByteLauncher implements PtyLauncher {
    private final PtyLauncher delegate;
    private final Runnable onFirstByte;
    private final AtomicBoolean seen = new AtomicBoolean();

    FirstByteLauncher(PtyLauncher delegate, Runnable onFirstByte) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.onFirstByte = Objects.requireNonNull(onFirstByte, "onFirstByte");
    }

    @Override
    public Result<TerminalStream, LaunchError> launch(LaunchCommand command, TerminalSize initialSize) {
        Result<TerminalStream, LaunchError> launched = delegate.launch(command, initialSize);
        if (!seen.get()) {
            launched.value().ifPresent(this::watch);
        }
        return launched;
    }

    // 시작 전에 붙이므로 첫 바이트부터 받는다(TerminalStream 계약). 셸마다 첫 조각에서 구독을 뗀다 — 먼저 받은 셸만 알린다
    private void watch(TerminalStream stream) {
        AtomicReference<Subscription> subscription = new AtomicReference<>(() -> {});
        subscription.set(stream.subscribe(chunk -> {
            subscription.get().cancel();
            if (seen.compareAndSet(false, true)) {
                onFirstByte.run();
            }
        }));
    }
}
