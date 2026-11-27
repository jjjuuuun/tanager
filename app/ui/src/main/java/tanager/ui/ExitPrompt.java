package tanager.ui;

/** 앱 창 닫기(X) 때 물을지(#89, M3 계획 U2). 셸 하나면 지금처럼 바로 끝낸다 — 여럿이면 한꺼번에 잃지 않게 묻는다. */
final class ExitPrompt {
    private ExitPrompt() {}

    static boolean needed(int runningShells) {
        return runningShells >= 2;
    }
}
