package tanager.archfixture.ui;

import tanager.application.port.out.ToolkitError;
import tanager.application.port.out.ToolkitProvider;
import tanager.application.port.out.ToolkitQuery;
import tanager.application.port.out.ToolkitScan;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.ProviderId;

/** 위반 픽스처: toolkit 어댑터 밖(ui)에서 도구함 공급자를 구현한다. */
public final class ProvidesToolkit implements ToolkitProvider {
    @Override
    public ProviderId id() {
        return new ProviderId("ui");
    }

    @Override
    public boolean supports(ShellEnvironment env) {
        return true;
    }

    @Override
    public Result<ToolkitScan, ToolkitError> scan(ToolkitQuery query) {
        return Result.err(new ToolkitError.Failed());
    }
}
