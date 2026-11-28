package tanager.application.port.out;

import java.util.Objects;
import tanager.domain.history.ShellEnvironment;

/** 그 환경의 홈을 알아내지 못한 이유({@link HostFiles#home}, #205). 화면 문장은 UI 가 만든다(#137). */
public sealed interface HomeError {

    /** 홈을 물은 환경. */
    ShellEnvironment env();

    /** 물을 명령을 띄우지 못했거나 실패로 끝났다(wsl.exe 없음 · 배포판 없음 등). 이 OS 에 없는 환경도 여기다. */
    record NotRunnable(ShellEnvironment env) implements HomeError {
        public NotRunnable {
            Objects.requireNonNull(env, "env");
        }
    }

    /** 시간 안에 답하지 않았다. */
    record TimedOut(ShellEnvironment env) implements HomeError {
        public TimedOut {
            Objects.requireNonNull(env, "env");
        }
    }

    /** 답이 그 환경의 절대 경로가 아니다(빈 값 · 상대 경로 · 쓸 수 없는 문자). */
    record Invalid(ShellEnvironment env) implements HomeError {
        public Invalid {
            Objects.requireNonNull(env, "env");
        }
    }
}
