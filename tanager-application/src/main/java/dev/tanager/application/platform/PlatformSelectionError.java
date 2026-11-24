package dev.tanager.application.platform;

import java.util.List;

/** 현재 OS 에 맞는 플랫폼 모듈을 하나로 정하지 못한 이유. */
public sealed interface PlatformSelectionError {

    /** 사람이 읽는 한 줄 설명(오류 대화상자 · 로그용). */
    String describe();

    /** 맞는 후보가 없다(지원하지 않는 OS 거나 플랫폼 모듈이 빠졌다). */
    record NoMatch(OperatingSystem os, List<String> candidates) implements PlatformSelectionError {
        public NoMatch {
            candidates = List.copyOf(candidates);
        }

        @Override
        public String describe() {
            return os.describe() + " 를 지원하는 플랫폼 모듈이 없습니다. 후보: " + candidates;
        }
    }

    /** 맞는 후보가 둘 이상이다(등록이 겹쳤다). */
    record Ambiguous(OperatingSystem os, List<String> matches) implements PlatformSelectionError {
        public Ambiguous {
            matches = List.copyOf(matches);
        }

        @Override
        public String describe() {
            return os.describe() + " 에 맞는 플랫폼 모듈이 여럿입니다: " + String.join(", ", matches);
        }
    }
}
