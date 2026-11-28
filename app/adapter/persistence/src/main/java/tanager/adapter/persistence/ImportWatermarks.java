package tanager.adapter.persistence;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tanager.domain.history.ImportOrigin;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * state.json 의 {@code history.importedUntil} — (출처, 환경)마다 마지막으로 가져온 시각(#207, SDD §9.2).
 * {@code [{"source": "claude-code", "env": {…}, "until": "ISO-8601"}]}.
 */
final class ImportWatermarks {
    private static final String SOURCE = "source";
    private static final String ENV = "env";
    private static final String UNTIL = "until";

    // 파일 순서: 출처 → 환경. Map 순회 순서와 무관하게 결정적이다.
    private static final Comparator<Map.Entry<ImportOrigin, Instant>> FILE_ORDER = Comparator.comparing(
                    (Map.Entry<ImportOrigin, Instant> e) -> e.getKey().source().id())
            .thenComparing(e -> Environments.orderKey(e.getKey().env()));

    private ImportWatermarks() {}

    /**
     * 없거나 배열이 아니면 빈 맵이다(이전 파일 호환). 틀린 원소는 버리고, 같은 (출처, 환경)이 여럿이면 가장 늦은 시각을 쓴다 — 워터마크가 낮아지면
     * 지운 경로가 되살아난다.
     */
    static Map<ImportOrigin, Instant> read(JsonNode node) {
        if (!(node instanceof ArrayNode array)) {
            return Map.of();
        }
        Map<ImportOrigin, Instant> out = new HashMap<>();
        for (JsonNode element : array) {
            if (!(element instanceof ObjectNode o)) {
                continue;
            }
            Optional<VisitSource> source = JsonValues.string(o.get(SOURCE)).flatMap(VisitSource::parse);
            Optional<ShellEnvironment> env = Environments.read(o.get(ENV));
            Optional<Instant> until = instant(o.get(UNTIL));
            if (source.isPresent() && env.isPresent() && until.isPresent()) {
                out.merge(new ImportOrigin(source.get(), env.get()), until.get(), ImportWatermarks::later);
            }
        }
        return Map.copyOf(out);
    }

    static ArrayNode write(Map<ImportOrigin, Instant> watermarks) {
        List<Map.Entry<ImportOrigin, Instant>> sorted = new ArrayList<>(watermarks.entrySet());
        sorted.sort(FILE_ORDER);
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (Map.Entry<ImportOrigin, Instant> e : sorted) {
            ObjectNode node = out.addObject();
            node.put(SOURCE, e.getKey().source().id());
            Environments.write(node.putObject(ENV), e.getKey().env());
            node.put(UNTIL, e.getValue().toString());
        }
        return out;
    }

    private static Optional<Instant> instant(JsonNode node) {
        try {
            return JsonValues.string(node).map(Instant::parse);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
