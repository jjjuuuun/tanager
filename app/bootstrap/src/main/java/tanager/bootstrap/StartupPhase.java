package tanager.bootstrap;

/** 시작 시간 로그의 단계(NFR-02, #221). 선언 순서가 로그에 적는 순서다. */
enum StartupPhase {
    /** {@code main} 진입. */
    MAIN("main"),
    /** state.json 읽기 끝. */
    STATE("state"),
    /** 셸 탐지 끝. */
    SHELLS("shells"),
    /** 첫 창 표시({@code windowOpened}). */
    WINDOW("window"),
    /** 첫 셸의 첫 바이트. */
    FIRST_BYTE("firstByte");

    private final String key;

    StartupPhase(String key) {
        this.key = key;
    }

    /** 로그에 적는 이름. */
    String key() {
        return key;
    }
}
