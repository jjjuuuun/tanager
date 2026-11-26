package dev.tanager.application.port.out;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * PTY 위에서 실행할 명령. {@link PlatformModule#launchCommand} 가 OS · 셸에 맞게 만들고 {@link PtyLauncher} 는 그대로
 * 실행한다(ADR-0007).
 *
 * @param argv 실행 파일과 인자. 비어 있지 않고 원소에 null 이 없으며 실행 파일은 공백이 아니다
 * @param workingDirectory 시작 디렉터리(호스트 OS 의 어휘상 절대 경로). 비면 앱의 현재 디렉터리
 * @param environment 앱의 환경 위에 덮어쓸 환경 변수. 이름은 공백이 아니고 {@code =} · NUL 이 없으며 값에 NUL 이 없다
 * @param envNames 호스트 OS 가 환경 변수 이름을 비교하는 방식
 */
public record LaunchCommand(
        List<String> argv, Optional<String> workingDirectory, Map<String, String> environment, EnvNames envNames) {

    /** 호스트 OS 가 환경 변수 이름을 비교하는 방식. Windows 는 대소문자를 무시하고 POSIX 는 구분한다. */
    public enum EnvNames {
        CASE_SENSITIVE,
        CASE_INSENSITIVE
    }

    // ADR-0007: 상속 환경의 TERM(dumb 등)보다 앞서고 environment 의 TERM 에는 진다
    private static final String TERM = "TERM";
    private static final String DEFAULT_TERM = "xterm-256color";

    public LaunchCommand {
        Objects.requireNonNull(argv, "argv");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(envNames, "envNames");
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
        workingDirectory.ifPresent(LaunchCommand::requireAbsolute);
        for (var entry : environment.entrySet()) {
            requireValidVariable(entry.getKey(), entry.getValue());
        }
        if (envNames == EnvNames.CASE_INSENSITIVE) {
            requireNoCaseCollision(environment);
        }
        argv = List.copyOf(argv);
        environment = Map.copyOf(environment);
    }

    /**
     * 상속 환경 위에 TERM 기본값과 {@link #environment} 를 차례로 덮은 새 환경. 입력은 바꾸지 않고 검증하지도 않는다(Windows 의
     * {@code =C:} 같은 특수 변수가 그대로 지나간다).
     */
    public Map<String, String> environmentOver(Map<String, String> inherited) {
        Map<String, String> merged = new HashMap<>(inherited);
        override(merged, TERM, DEFAULT_TERM);
        for (var entry : environment.entrySet()) {
            override(merged, entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(merged);
    }

    // merged 는 environmentOver 안에서만 쓰는 지역 맵이다
    private void override(Map<String, String> merged, String name, String value) {
        boolean ignoreCase = switch (envNames) {
            case CASE_INSENSITIVE -> true;
            case CASE_SENSITIVE -> false;
        };
        if (ignoreCase) {
            // 대소문자만 다른 이름이 둘 남으면 자식 프로세스가 어느 쪽을 볼지 알 수 없다 — 덮는 이름 하나만 남긴다
            String key = nameKey(name);
            merged.keySet().removeIf(existing -> nameKey(existing).equals(key));
        }
        merged.put(name, value);
    }

    // Windows 는 환경 변수 이름을 글자마다 단순 대문자 표로 비교한다(RtlUpcaseUnicodeChar). 로캘 · 특수 대소문자(ß → SS,
    // İ → i̇)를 쓰지 않으려고 코드 포인트마다 Character.toUpperCase 를 쓴다 — 생성 검사와 병합이 같은 키를 쓴다.
    private static String nameKey(String name) {
        StringBuilder key = new StringBuilder(name.length());
        name.codePoints().map(Character::toUpperCase).forEach(key::appendCodePoint);
        return key.toString();
    }

    // 대소문자를 무시하는 OS 에서 PATH 와 Path 를 함께 주면 어느 쪽이 이길지 정할 수 없다(Map.copyOf 순서는 정해져 있지 않다)
    private static void requireNoCaseCollision(Map<String, String> environment) {
        Set<String> seen = new HashSet<>();
        for (String name : environment.keySet()) {
            if (!seen.add(nameKey(name))) {
                throw new IllegalArgumentException("대소문자만 다른 환경 변수 이름이 있다: " + name);
            }
        }
    }

    // 실제 존재 여부는 런처가 확인한다 — 여기서는 I/O 없이 모양만 본다
    private static void requireAbsolute(String directory) {
        boolean posix = directory.startsWith("/");
        boolean unc = directory.startsWith("\\\\");
        boolean drive = directory.length() >= 3
                && isAsciiLetter(directory.charAt(0))
                && directory.charAt(1) == ':'
                && directory.charAt(2) == '\\';
        if (!(posix || unc || drive)) {
            throw new IllegalArgumentException("작업 디렉터리가 절대 경로가 아니다: " + directory);
        }
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static void requireValidVariable(String name, String value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("환경 변수 이름이 비어 있다");
        }
        if (name.indexOf('=') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("환경 변수 이름에 = 나 NUL 이 있다");
        }
        if (value == null) {
            throw new IllegalArgumentException("환경 변수 값이 null 이다: " + name);
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("환경 변수 값에 NUL 이 있다: " + name);
        }
    }
}
