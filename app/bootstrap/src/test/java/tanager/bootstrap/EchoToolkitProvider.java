package tanager.bootstrap;

import java.util.List;
import java.util.Optional;
import tanager.application.port.out.ToolkitError;
import tanager.application.port.out.ToolkitProvider;
import tanager.application.port.out.ToolkitQuery;
import tanager.application.port.out.ToolkitScan;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.ProviderId;
import tanager.domain.toolkit.SourceRef;
import tanager.domain.toolkit.ToolkitItem;
import tanager.domain.toolkit.ToolkitKind;
import tanager.domain.toolkit.ToolkitScope;

/**
 * 조립이 {@code ServiceLoader} 로 공급자를 찾는지 보는 테스트용 공급자(#209). 테스트 리소스의 {@code META-INF/services} 로 등록한다. Windows
 * 만 지원하고, 받은 경로를 원본으로 하는 항목 {@code echo} 하나를 돌려준다.
 */
public final class EchoToolkitProvider implements ToolkitProvider {
    static final ProviderId ID = new ProviderId("echo");

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public boolean supports(ShellEnvironment env) {
        return env instanceof ShellEnvironment.Windows;
    }

    @Override
    public Result<ToolkitScan, ToolkitError> scan(ToolkitQuery query) {
        ToolkitItem item = new ToolkitItem(
                ID,
                ToolkitKind.SKILL,
                "echo",
                ToolkitScope.PROJECT,
                Optional.empty(),
                new SourceRef(query.path()),
                List.of(),
                false);
        return Result.ok(new ToolkitScan(List.of(item), List.of(), Optional.empty()));
    }
}
