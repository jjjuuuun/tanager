package tanager.archfixture.ui;

import java.nio.file.Path;
import tanager.application.port.out.ConversationError;
import tanager.application.port.out.ConversationScan;
import tanager.application.port.out.ConversationSource;
import tanager.domain.history.ConversationId;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitSource;
import tanager.domain.session.ShellLine;
import tanager.domain.shared.Result;

/** 위반 픽스처: toolkit 어댑터 밖(ui)에서 대화 기록 공급자를 구현한다. */
public final class RogueProvider implements ConversationSource {
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
