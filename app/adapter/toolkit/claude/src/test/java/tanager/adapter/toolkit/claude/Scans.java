package tanager.adapter.toolkit.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import tanager.application.port.out.ToolkitError;
import tanager.application.port.out.ToolkitScan;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.Attribute;
import tanager.domain.toolkit.AttributeValue;
import tanager.domain.toolkit.SourceRef;
import tanager.domain.toolkit.ToolkitItem;
import tanager.domain.toolkit.ToolkitKind;
import tanager.domain.toolkit.ToolkitScope;

/** 공급자 시험이 scan 결과에서 항목을 찾는 도우미. */
final class Scans {

    private Scans() {}

    static ToolkitScan ok(Result<ToolkitScan, ToolkitError> result) {
        assertThat(result.error()).isEmpty();
        return result.value().orElseThrow();
    }

    /** 그 종류 · 범위 · 이름의 항목 하나(정확히 하나여야 한다). */
    static ToolkitItem item(ToolkitScan scan, ToolkitKind kind, ToolkitScope scope, String name) {
        List<ToolkitItem> found = scan.items().stream()
                .filter(i -> i.kind() == kind && i.scope() == scope && i.name().equals(name))
                .toList();
        assertThat(found).as("%s %s %s", kind, scope, name).hasSize(1);
        return found.getFirst();
    }

    /** 그 종류 · 범위의 항목(scan 순서). */
    static List<ToolkitItem> items(ToolkitScan scan, ToolkitKind kind, ToolkitScope scope) {
        return scan.items().stream()
                .filter(i -> i.kind() == kind && i.scope() == scope)
                .toList();
    }

    /** 그 종류 · 범위의 이름(정렬). */
    static List<String> names(ToolkitScan scan, ToolkitKind kind, ToolkitScope scope) {
        return items(scan, kind, scope).stream().map(ToolkitItem::name).sorted().toList();
    }

    static SourceRef source(WorkspacePath base, String relative) {
        return new SourceRef(ClaudeFixture.path(base.env(), base.path() + "/" + relative));
    }

    static Attribute plain(String key, String value) {
        return new Attribute(key, new AttributeValue.Plain(value));
    }
}
