package tanager.domain.history;

import java.util.Objects;

/** 셸이 도는 환경. 같은 경로 문자열이라도 환경이 다르면 다른 작업 경로다(구분자 · 대소문자 규칙이 다르다). */
public sealed interface ShellEnvironment {

    /** WSL 배포판 하나. 경로는 '/' 구분, 대소문자 구분. */
    record Wsl(String distro) implements ShellEnvironment {
        public Wsl {
            Objects.requireNonNull(distro, "distro");
            if (distro.isBlank()) {
                throw new IllegalArgumentException("WSL 배포판 이름이 비어 있다");
            }
        }
    }

    /** Windows 호스트(PowerShell 5.1 · cmd). 경로는 '\' 구분, 대소문자 무시. */
    record Windows() implements ShellEnvironment {}
}
