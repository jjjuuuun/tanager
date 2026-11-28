package tanager.application.port.out;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import tanager.domain.history.VisitSource;

/**
 * 도구함 설정(config {@code toolkit}, SDD §9.3). providers 가 비면 "등록된 공급자 모두"다 — 빈 배열을 적어도 도구함이 꺼지지는 않는다.
 * 불변식: providers 원소 중복 없음(순서 유지). 원소는 공급자 id 원문이다 — 형식 검사는 저장소가 읽을 때 하고, 아직 설치되지 않은 공급자
 * id 도 담을 수 있다.
 *
 * @param providers 도구함에 쓸 공급자 id(파일 순서)
 * @param importClaudeProjects Claude Code 작업 이력 · 대화 목록을 가져오는가(FR-TK-09)
 */
public record ToolkitConfig(List<String> providers, boolean importClaudeProjects) {
    private static final ToolkitConfig DEFAULTS = new ToolkitConfig(List.of(), true);

    public ToolkitConfig {
        providers = List.copyOf(providers);
        if (new HashSet<>(providers).size() != providers.size()) {
            throw new IllegalArgumentException("providers 에 같은 공급자가 두 번 있다: " + providers);
        }
    }

    /**
     * 그 출처의 이전 대화를 가져오는가(FR-TK-09, #215). providers 가 비지 않았으면 출처 id 가 들어 있어야 하고, Claude Code 출처는
     * {@code importClaudeProjects} 도 켜져 있어야 한다.
     */
    public boolean importsConversations(VisitSource source) {
        Objects.requireNonNull(source, "source");
        boolean chosen = providers.isEmpty() || providers.contains(source.id());
        return chosen && (importClaudeProjects || !source.equals(VisitSource.CLAUDE_CODE));
    }

    /** SDD §9.3 의 기본값: 등록된 공급자 모두, 대화 가져오기 켬. */
    public static ToolkitConfig defaults() {
        return DEFAULTS;
    }
}
