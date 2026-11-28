package tanager.application.port.out;

/**
 * 공급자가 그 경로의 구성을 통째로 읽지 못한 이유({@link ToolkitProvider#scan}). 조회는 그 공급자의 {@code ProviderFailed} 경고로 바꾼다.
 * 경로 · 내용은 담지 않는다 — 비밀값이 섞일 수 있다(ADR-0019). 화면 문장은 UI 가 만든다(#137).
 */
public sealed interface ToolkitError {

    /** 개발자용 설명(로그). 경로 · 내용은 넣지 않는다. */
    String describe();

    /** 그 경로나 홈에 닿지 못했다(앱이 열 수 없는 경로 · 배포판이 응답하지 않음). */
    record Unreachable() implements ToolkitError {
        @Override
        public String describe() {
            return "도구함 파일에 닿지 못했다";
        }
    }

    /** 그 밖의 이유로 읽지 못했다(구성 형식을 통째로 모름 등). */
    record Failed() implements ToolkitError {
        @Override
        public String describe() {
            return "도구함 구성을 읽지 못했다";
        }
    }
}
