package dev.tanager.application.platform;

import dev.tanager.application.port.out.PlatformModule;
import dev.tanager.domain.shared.Result;
import java.util.ArrayList;
import java.util.List;

/** 등록된 플랫폼 모듈 중 현재 OS 를 지원하는 것을 정확히 하나 고른다(순수 함수). */
public final class PlatformSelection {
    private PlatformSelection() {}

    public static Result<PlatformModule, PlatformSelectionError> select(
            List<? extends PlatformModule> candidates, OperatingSystem os) {
        List<PlatformModule> matches = new ArrayList<>();
        for (PlatformModule candidate : candidates) {
            if (candidate.supports(os)) {
                matches.add(candidate);
            }
        }
        if (matches.size() == 1) {
            return Result.ok(matches.getFirst());
        }
        if (matches.isEmpty()) {
            return Result.err(new PlatformSelectionError.NoMatch(os, ids(candidates)));
        }
        return Result.err(new PlatformSelectionError.Ambiguous(os, ids(matches)));
    }

    private static List<String> ids(List<? extends PlatformModule> modules) {
        List<String> ids = new ArrayList<>();
        for (PlatformModule m : modules) {
            ids.add(m.id());
        }
        return ids;
    }
}
