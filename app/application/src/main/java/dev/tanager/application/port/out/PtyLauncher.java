package dev.tanager.application.port.out;

import dev.tanager.domain.shared.Result;

/**
 * 의사 터미널(PTY) 위에서 프로세스를 띄우는 출력 포트(ADR-0007). OS · 셸 종류를 모르고 받은 명령을 그대로 실행한다.
 */
public interface PtyLauncher {

    /**
     * {@code command} 를 {@code initialSize} 크기의 PTY 에서 띄운다. 출력은 {@link TerminalStream#start()} 를 불러야
     * 읽기 시작한다.
     *
     * <p>예외를 던지지 않고 실패를 {@link LaunchError} 로 돌려준다. 프로세스를 만드느라 잠깐 블로킹될 수 있다.
     */
    Result<TerminalStream, LaunchError> launch(LaunchCommand command, TerminalSize initialSize);
}
