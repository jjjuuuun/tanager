package tanager.application.port.out;

/** 다른 스레드의 일을 UI 스레드(EDT)로 넘긴다. ui 모듈이 {@code SwingUtilities.invokeLater} 로 구현한다. */
@FunctionalInterface
public interface UiDispatcher {

    /** {@code task} 를 UI 스레드에서 나중에 돌린다. 어느 스레드에서 불러도 되고 막지 않는다. 넘긴 순서대로 돈다. */
    void dispatch(Runnable task);
}
