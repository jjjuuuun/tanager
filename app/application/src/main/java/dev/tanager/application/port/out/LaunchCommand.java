package dev.tanager.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * PTY 위에서 실행할 명령. {@link PlatformModule#launchCommand} 가 OS · 셸에 맞게 만들고 {@link PtyLauncher} 는 그대로
 * 실행한다(ADR-0007).
 *
 * @param argv 실행 파일과 인자. 비어 있지 않고 원소에 null 이 없으며 실행 파일은 공백이 아니다
 * @param workingDirectory 시작 디렉터리(호스트 OS 경로). 비면 앱의 현재 디렉터리
 * @param environment 앱의 환경 위에 덮어쓸 환경 변수
 */
public record LaunchCommand(List<String> argv, Optional<String> workingDirectory, Map<String, String> environment) {
    public LaunchCommand {
        Objects.requireNonNull(argv, "argv");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(environment, "environment");
        // List.copyOf 는 null 원소에 NPE 를 던진다 — 잘못된 인자는 IAE 로 알리려고 먼저 검사한다
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv 가 비어 있다");
        }
        // 불변 리스트의 contains(null) 도 NPE 를 던지므로 직접 훑는다
        for (String arg : argv) {
            if (arg == null) {
                throw new IllegalArgumentException("argv 에 null 원소가 있다");
            }
        }
        if (argv.getFirst().isBlank()) {
            throw new IllegalArgumentException("실행 파일이 비어 있다");
        }
        for (var entry : environment.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("환경 변수 이름이 비어 있다");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("환경 변수 값이 null 이다: " + entry.getKey());
            }
        }
        argv = List.copyOf(argv);
        environment = Map.copyOf(environment);
    }
}
