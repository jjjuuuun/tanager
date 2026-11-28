package tanager.ui.keys;

import tanager.ui.i18n.Msg;

/** 키 설정 문제 → 상태줄 알림 문장(#183). 동작 id 와 키 원문은 사용자가 config.json 에 쓴 그대로 보인다. */
public final class KeyProblemText {
    private KeyProblemText() {}

    /** {@code problem} 의 화면 문장. */
    public static String of(KeyProblem problem) {
        return switch (problem) {
            case KeyProblem.Unparseable(String action, String raw) -> Msg.KEYS_UNPARSEABLE.text(action, raw);
            case KeyProblem.UnknownAction(String action) -> Msg.KEYS_UNKNOWN_ACTION.text(action);
            case KeyProblem.Reserved(String action, String key) -> Msg.KEYS_RESERVED.text(action, key);
            case KeyProblem.Conflict(String key, var actions) ->
                Msg.KEYS_CONFLICT.text(key, String.join(", ", actions));
            case KeyProblem.Unbound(String action, String key, String by) -> Msg.KEYS_UNBOUND.text(action, key, by);
            case KeyProblem.AltGraph(String action, String key) -> Msg.KEYS_ALT_GRAPH.text(action, key);
            case KeyProblem.PrefixUnparseable(String raw) -> Msg.KEYS_PREFIX_UNPARSEABLE.text(raw);
            case KeyProblem.PrefixReserved(String key) -> Msg.KEYS_PREFIX_RESERVED.text(key);
            case KeyProblem.PrefixConflict(String key, String action) -> Msg.KEYS_PREFIX_CONFLICT.text(key, action);
        };
    }
}
