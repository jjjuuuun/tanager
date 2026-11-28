package tanager.ui.measure;

/**
 * 창 하나의 입력 반응 측정 고리(NFR-01, #222). 터미널 위젯이 키 · 출력 · 그림 시점을 알린다. 측정 모드가 꺼져 있으면 {@link #OFF} 이고
 * 위젯은 출력 고리를 달지 않는다.
 */
public sealed interface PaneMeter permits PaneMeter.Off, InputLatencyProbe {
    /** 아무것도 재지 않는다. */
    PaneMeter OFF = Off.INSTANCE;

    /** 키 하나({@code KEY_TYPED})를 셸로 보낸다. EDT. */
    void keyTyped();

    /** 셸 출력이 화면 버퍼에 들어갔다. 에뮬레이터 스레드. */
    void output();

    /** 화면을 다 그렸다. EDT. */
    void painted();

    /** 측정 모드가 꺼져 있을 때의 고리. */
    enum Off implements PaneMeter {
        INSTANCE;

        @Override
        public void keyTyped() {}

        @Override
        public void output() {}

        @Override
        public void painted() {}
    }
}
