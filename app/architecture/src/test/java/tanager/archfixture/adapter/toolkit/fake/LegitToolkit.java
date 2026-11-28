package tanager.archfixture.adapter.toolkit.fake;

import tanager.application.port.out.ToolkitError;
import tanager.application.port.out.ToolkitProvider;
import tanager.application.port.out.ToolkitQuery;
import tanager.application.port.out.ToolkitScan;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.shared.Result;
import tanager.domain.toolkit.ProviderId;

/** 허용 픽스처: toolkit 어댑터 안의 도구함 공급자. */
public final class LegitToolkit implements ToolkitProvider {
    @Override
    public ProviderId id() {
        return new ProviderId("fake");
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
