package dev.tanager.application.port.out;

/** {@link Scheduler} 로 예약한 일의 취소 손잡이. */
@FunctionalInterface
public interface Cancellable {

    /** 아직 돌지 않았으면 돌지 않게 한다. 여러 번 불러도 되고 이미 돈 뒤에 불러도 된다. */
    void cancel();
}
