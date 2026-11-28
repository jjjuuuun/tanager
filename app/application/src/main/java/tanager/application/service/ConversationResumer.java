package tanager.application.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import tanager.application.port.in.OpenError;
import tanager.application.port.in.ResumeConversationUseCase;
import tanager.application.port.out.ConversationSource;
import tanager.application.port.out.OpenPlacement;
import tanager.domain.history.PastConversation;
import tanager.domain.history.VisitSource;
import tanager.domain.history.WorkspacePath;
import tanager.domain.session.ShellLine;
import tanager.domain.shared.Result;
import tanager.domain.workspace.PaneSpec;

/**
 * 이전 대화를 새 창에서 이어 한다(FR-TK-09, #216). 명령은 그 출처의 {@link ConversationSource} 가 정한다 — application 은 {@code claude}
 * 를 모른다. 창은 {@link PaneManager} 가 열고, 새 창이 생기는 상태 전이 안에서 {@link StartupCommands} 에 명령을 단다 — UI 가 그 창의 셸을
 * 띄우기 전이다.
 */
public final class ConversationResumer implements ResumeConversationUseCase {
    private final PaneManager panes;
    private final List<ConversationSource> sources;
    private final StartupCommands commands;

    public ConversationResumer(PaneManager panes, List<ConversationSource> sources, StartupCommands commands) {
        this.panes = Objects.requireNonNull(panes, "panes");
        this.sources = List.copyOf(sources);
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    @Override
    public CompletionStage<Result<PaneSpec, OpenError>> resume(
            PastConversation conversation, Optional<OpenPlacement> placement) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(placement, "placement");
        return openWith(
                conversation.source(), source -> source.resume(conversation.id()), conversation.cwd(), placement);
    }

    @Override
    public CompletionStage<Result<PaneSpec, OpenError>> continueLatest(
            WorkspacePath folder, VisitSource source, Optional<OpenPlacement> placement) {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(placement, "placement");
        return openWith(source, ConversationSource::continueLatest, folder, placement);
    }

    private CompletionStage<Result<PaneSpec, OpenError>> openWith(
            VisitSource source,
            Function<ConversationSource, ShellLine> command,
            WorkspacePath folder,
            Optional<OpenPlacement> placement) {
        Optional<ConversationSource> found = sources.stream()
                .filter(candidate -> candidate.source().equals(source))
                .findFirst();
        if (found.isEmpty()) {
            return CompletableFuture.completedStage(Result.err(new OpenError.UnknownSource(source)));
        }
        ShellLine line = command.apply(found.get());
        return panes.open(folder, placement, opened -> commands.attach(opened.pane(), folder, line));
    }
}
