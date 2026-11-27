package tanager.application.port.out;

/** 설정 파일(config.json)이 바뀌면 알리는 출력 포트(FR-CFG-03, ADR-0009 개정). */
public interface ConfigWatch {

    /**
     * 감시를 시작한다. 파일을 만들거나 고치거나 다른 이름에서 바꿔 넣으면(편집기의 원자적 저장) 잠깐 모았다가 {@code changed} 를 한 번 부른다.
     * 콜백은 가상 스레드에서 돈다. 감시를 시작하지 못하면 알리지 않는 구독을 돌려준다(앱은 그대로 돈다).
     */
    Subscription watch(Runnable changed);
}
