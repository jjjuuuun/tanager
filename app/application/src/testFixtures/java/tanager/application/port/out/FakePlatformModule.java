package tanager.application.port.out;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import tanager.application.platform.OperatingSystem;
import tanager.domain.history.PathTranslator;
import tanager.domain.history.WslMount;
import tanager.domain.session.ShellProfile;
import tanager.domain.session.WorkingDir;

/**
 * {@link PlatformModule} 의 수작업 Fake. 셸 목록을 지정하고 {@code shells()} 호출 수와 {@code launchCommand} 인자를 기록한다.
 *
 * <p>{@code launchCommand} 규칙: argv 는 {@code [실행 파일, 템플릿 인자...]} 이고 Home 이면 {@code "--home"} 이 붙는다. 작업
 * 디렉터리는 {@code At} 이면 그 경로 문자열, Home 이면 비어 있다. 경로 환경이 프로파일과 다르면 포트 계약대로
 * {@link IllegalArgumentException}. 셸 통합 결과({@code integration})는 argv 에 영향을 주지 않고 {@link
 * #launchIntegrations} 로만 기록한다. 스레드 안전.
 */
public final class FakePlatformModule implements PlatformModule {
    private final List<ShellProfile> shells;
    private final AtomicInteger shellsCalls = new AtomicInteger();
    private final List<LaunchRequest> launchRequests = new ArrayList<>();
    private final List<Integration> launchIntegrations = new ArrayList<>();
    private Path appData = Path.of("fake-appdata", "Tanager");
    private ShellIntegration defaultIntegration = defaultIntegrationFor(appData);
    private Optional<ShellIntegration> customIntegration = Optional.empty();
    private CountDownLatch shellsGate;
    private Optional<RuntimeException> shellsFailure = Optional.empty();

    public FakePlatformModule(List<ShellProfile> shells) {
        this.shells = List.copyOf(shells);
    }

    /** {@code shells()} 가 이 래치가 열릴 때까지 막힌다(호출 수는 막히기 전에 센다). 동시 호출 테스트용. */
    public synchronized FakePlatformModule blockShellsUntil(CountDownLatch release) {
        this.shellsGate = release;
        return this;
    }

    /** {@code shells()} 가 이 예외를 던진다(포트 계약 위반 흉내 — 호출 수는 센다). */
    public synchronized FakePlatformModule failShellsWith(RuntimeException failure) {
        this.shellsFailure = Optional.of(failure);
        return this;
    }

    /** 앱 데이터 폴더. {@link #withIntegration} 을 주지 않았다면 기본 {@code integration()} 도 이 아래를 가리킨다. */
    public synchronized FakePlatformModule withAppData(Path appData) {
        this.appData = appData;
        this.defaultIntegration = defaultIntegrationFor(appData);
        return this;
    }

    /**
     * {@code integration()} 이 돌려줄 셸 통합. 주지 않으면 {@code Installed(<appData>/shell-integration)} 을 돌려주는 Fake 다(기본
     * appData 는 {@code fake-appdata/Tanager}).
     */
    public synchronized FakePlatformModule withIntegration(ShellIntegration integration) {
        this.customIntegration = Optional.of(Objects.requireNonNull(integration, "integration"));
        return this;
    }

    private static ShellIntegration defaultIntegrationFor(Path appData) {
        return FakeShellIntegration.installedAt(appData.resolve("shell-integration"));
    }

    /** {@code launchCommand} 에 넘어온 인자 하나. */
    public record LaunchRequest(ShellProfile profile, WorkingDir dir) {}

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public boolean supports(OperatingSystem os) {
        return true;
    }

    @Override
    public List<ShellProfile> shells() {
        shellsCalls.incrementAndGet();
        CountDownLatch gate;
        Optional<RuntimeException> failure;
        synchronized (this) {
            gate = shellsGate;
            failure = shellsFailure;
        }
        if (failure.isPresent()) {
            throw failure.get();
        }
        if (gate != null) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return shells;
    }

    @Override
    public synchronized LaunchCommand launchCommand(ShellProfile profile, WorkingDir dir, Integration integration) {
        Objects.requireNonNull(integration, "integration");
        // 포트 계약과 같게: 다른 환경의 경로는 거부한다(기록하지 않는다)
        if (dir instanceof WorkingDir.At at && !at.path().env().equals(profile.env())) {
            throw new IllegalArgumentException(
                    "프로파일 환경 " + profile.env() + " 과 경로 환경 " + at.path().env() + " 이 다르다");
        }
        launchRequests.add(new LaunchRequest(profile, dir));
        launchIntegrations.add(integration);
        List<String> argv = new ArrayList<>();
        argv.add(profile.launch().executable());
        argv.addAll(profile.launch().args());
        Optional<String> workingDirectory = Optional.empty();
        switch (dir) {
            case WorkingDir.Home _ -> argv.add("--home");
            case WorkingDir.At at -> workingDirectory = Optional.of(at.path().path());
        }
        return new LaunchCommand(argv, workingDirectory, Map.of(), LaunchCommand.EnvNames.CASE_SENSITIVE);
    }

    @Override
    public synchronized PlatformDirs dirs() {
        Path dir = appData;
        return () -> dir;
    }

    /** {@code shells()} 가 불린 횟수. */
    public int shellsCalls() {
        return shellsCalls.get();
    }

    /** {@code launchCommand} 호출 기록(불변 사본). */
    public synchronized List<LaunchRequest> launchRequests() {
        return List.copyOf(launchRequests);
    }

    /** {@code launchCommand} 호출마다 받은 셸 통합 결과(호출 순서, 불변 사본). 인자는 {@link LaunchRequest} 와 같은 인덱스다. */
    public synchronized List<Integration> launchIntegrations() {
        return List.copyOf(launchIntegrations);
    }

    @Override
    public synchronized ShellIntegration integration() {
        return customIntegration.orElse(defaultIntegration);
    }

    /** Windows 와 같은 규칙(마운트 루트 /mnt/). */
    @Override
    public PathTranslation paths() {
        return (from, to) -> PathTranslator.translate(from, to, WslMount.DEFAULT);
    }
}
