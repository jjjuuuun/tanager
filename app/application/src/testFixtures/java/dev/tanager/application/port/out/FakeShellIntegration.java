package dev.tanager.application.port.out;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** {@link ShellIntegration} 의 수작업 Fake. 정해 둔 결과를 돌려주고 {@code install()} 호출 수를 센다. 스레드 안전. */
public final class FakeShellIntegration implements ShellIntegration {
    private final Integration result;
    private final AtomicInteger installCalls = new AtomicInteger();

    public FakeShellIntegration(Integration result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    /** 항상 {@code Installed(directory)} 를 돌려준다. */
    public static FakeShellIntegration installedAt(Path directory) {
        return new FakeShellIntegration(new Integration.Installed(directory));
    }

    /** 항상 {@code Unavailable(reason)} 을 돌려준다. */
    public static FakeShellIntegration unavailable(String reason) {
        return new FakeShellIntegration(new Integration.Unavailable(reason));
    }

    @Override
    public Integration install() {
        installCalls.incrementAndGet();
        return result;
    }

    public int installCalls() {
        return installCalls.get();
    }
}
