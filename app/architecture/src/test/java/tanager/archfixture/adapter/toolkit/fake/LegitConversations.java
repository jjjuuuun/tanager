package tanager.archfixture.adapter.toolkit.fake;

import java.nio.file.Path;
import tanager.application.port.out.ConversationError;
import tanager.application.port.out.ConversationScan;
import tanager.application.port.out.ConversationSource;
import tanager.domain.history.ConversationId;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.session.ShellLine;
import tanager.domain.shared.Result;

/** 허용 픽스처: toolkit 어댑터 안의 대화 기록 공급자. */
public final class LegitConversations implements ConversationSource {
    @Override
    public VisitSource source() {
        return VisitSource.CLAUDE_CODE;
    }

    @Override
    public boolean supports(ShellEnvironment env) {
        return true;
    }

    @Override
    public Result<ConversationScan, ConversationError> scan(ShellEnvironment env, Path hostHome) {
        return Result.err(new ConversationError.NoHistory());
    }

    @Override
    public ShellLine resume(ConversationId id) {
        return new ShellLine("resume");
    }

    @Override
    public ShellLine continueLatest() {
        return new ShellLine("continue");
    }
}
