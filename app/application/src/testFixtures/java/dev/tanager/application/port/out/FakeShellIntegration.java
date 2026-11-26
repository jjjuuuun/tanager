package dev.tanager.application.port.out;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * {@link ShellIntegration} 의 수작업 Fake. 정해 둔 결과를 돌려주거나(포트 계약 위반을 흉내 내) 예외를 던지고, {@code install()} 호출
 * 수를 센다. 스레드 안전.
 */
public final class FakeShellIntegration implements ShellIntegration {
    private final Supplier<Integration> outcome;
    private final AtomicInteger installCalls = new AtomicInteger();

    public FakeShellIntegration(Integration result) {
        this(constant(Objects.requireNonNull(result, "result")));
    }

    private FakeShellIntegration(Supplier<Integration> outcome) {
        this.outcome = outcome;
    }

    private static Supplier<Integration> constant(Integration result) {
        return () -> result;
    }

    /** 항상 {@code Installed(directory)} 를 돌려준다. */
    public static FakeShellIntegration installedAt(Path directory) {
        return new FakeShellIntegration(new Integration.Installed(directory));
    }

    /** 항상 {@code Unavailable(reason)} 을 돌려준다. */
    public static FakeShellIntegration unavailable(String reason) {
        return new FakeShellIntegration(new Integration.Unavailable(reason));
    }

    /** {@code install()} 이 이 예외를 던진다(포트 계약 위반 흉내 — 호출 수는 센다). */
    public static FakeShellIntegration throwing(RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        return new FakeShellIntegration(() -> {
            throw failure;
        });
    }

    @Override
    public Integration install() {
        installCalls.incrementAndGet();
        return outcome.get();
    }

    public int installCalls() {
        return installCalls.get();
    }
}
