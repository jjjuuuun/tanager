package tanager.application.port.out;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 실행 중인 PTY 프로세스 하나의 입출력(ADR-0007). 출력은 push 구독으로, 입력 · 크기 변경 · 종료는 메서드로 다룬다.
 *
 * <p>스레드 계약:
 *
 * <ul>
 *   <li>구독 콜백은 그 세션의 읽기 스레드에서 한 번에 하나씩, 바이트 순서대로 온다. EDT 에서는 오지 않는다.
 *   <li>조각({@code byte[]})은 구독자마다 새 복사본이다. 받은 쪽이 보관하거나 고쳐도 된다.
 *   <li>구독자가 예외나 {@code Error}(가상 머신 오류 제외)를 던지면 그 구독자만 해제한다. 다른 구독자와 읽기는 계속된다.
 *   <li>구독자가 오래 막히면 그만큼 셸 출력도 멈춘다(배압).
 *   <li>{@link #write} · {@link #resize} · {@link #close} 는 어느 스레드에서 불러도 된다. 끝난 뒤에는 아무 일도 하지 않는다.
 * </ul>
 *
 * <p>바이트는 UTF-8 이다. {@link PtyLauncher#launch} 가 준 스트림은 시작하지 않았더라도 반드시 {@link #close()} 한다 — 그래야
 * 프로세스와 PTY 가 정리된다.
 */
public interface TerminalStream {

    /**
     * 출력 구독자를 붙인다. {@link #start()} 전에 붙인 구독자는 첫 바이트부터 받는다. 끝난 스트림에 붙이면 콜백이 오지 않는
     * 구독을 돌려준다.
     */
    Subscription subscribe(Consumer<byte[]> onOutput);

    /**
     * 출력 읽기를 시작한다. 한 번만 부를 수 있다 — 두 번째는 {@link IllegalStateException}. {@link #close()} 뒤에는 아무 일도 하지
     * 않는다.
     */
    void start();

    /** 셸 입력으로 바이트를 쓴다. 셸이 입력을 읽지 않아 입력 파이프가 차면 블로킹될 수 있으므로 EDT 밖에서 부른다. */
    void write(byte[] bytes);

    /** PTY 크기를 바꾼다. */
    void resize(TerminalSize size);

    /**
     * 프로세스와 그 자손을 끝낸다. 기다리지 않고 바로 돌아오며 여러 번 불러도 된다. 이 뒤로 {@link #onExit()} 가 완료된다(종료
     * 코드는 의미 없다). 이미 끝난 스트림에서는 아무 일도 하지 않는다. 그때 전달 중이던 조각 하나는 close 직후에도 올 수 있다.
     */
    void close();

    /**
     * 프로세스가 끝나면 종료 코드로 정확히 한 번 완료된다. 예외로 끝나지 않는다. 모든 출력이 모든 구독자에게 전달된 뒤에
     * 완료된다(happens-before). 읽기 전용이다 — 받은 쪽이 완료시켜도 실제 종료 코드에는 영향이 없다.
     */
    CompletionStage<Integer> onExit();

    /** 루트 프로세스의 OS 프로세스 ID. {@link #start()} 전에도 알 수 있다. */
    long pid();
}
