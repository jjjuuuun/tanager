package tanager.application.port.out;

/** 대화 기록을 통째로 읽지 못한 이유({@link ConversationSource#scan}). 부르는 쪽은 그 환경의 대화 기능을 끈다. 화면 문장은 UI 가 만든다(#137). */
public sealed interface ConversationError {

    /** 개발자용 설명(로그). 경로 · 내용은 넣지 않는다. */
    String describe();

    /** 기록 폴더가 없다 — 그 환경에서 도구를 쓴 적이 없다. */
    record NoHistory() implements ConversationError {
        @Override
        public String describe() {
            return "대화 기록 폴더가 없다";
        }
    }

    /** 기록 파일은 있는데 어느 파일에서도 아는 형식을 찾지 못했다 — 도구가 형식을 바꿨을 수 있다. */
    record UnknownFormat() implements ConversationError {
        @Override
        public String describe() {
            return "대화 기록 형식을 알아보지 못했다";
        }
    }

    /** 기록 폴더나 파일을 읽을 수 없다(권한 · I/O). */
    record Unreadable() implements ConversationError {
        @Override
        public String describe() {
            return "대화 기록을 읽을 수 없다";
        }
    }
}
