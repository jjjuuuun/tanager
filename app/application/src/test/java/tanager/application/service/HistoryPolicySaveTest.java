package tanager.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.application.port.out.AppConfig;
import tanager.application.port.out.FakeScheduler;
import tanager.application.port.out.HistoryConfig;
import tanager.application.port.out.InMemoryHistoryRepository;
import tanager.application.port.out.LoadedConfig;
import tanager.application.state.AppState;
import tanager.application.state.HistoryStore;
import tanager.application.state.ManualExecutor;
import tanager.application.state.SettingsLoaded;
import tanager.application.state.StateActor;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;

/** 설정에서 이력 한도를 줄이면 줄어든 이력이 1초 안에 저장된다(FR-HIS-09, #186). */
class HistoryPolicySaveTest {
    private static final ShellEnvironment WIN = new ShellEnvironment.Windows();
    private static final Instant T0 = Instant.parse("2026-11-28T00:00:00Z");

    @Test
    @DisplayName("config 의 history.maxEntries 를 줄여 저장하면 1초 안에 줄어든 이력이 저장된다")
    void shrinkSavedWithinOneSecond() {
        // given
        WorkHistory h = WorkHistory.empty(HistoryPolicy.DEFAULT);
        for (int i = 0; i < 105; i++) {
            h = h.recordVisit(WorkspacePath.of(WIN, "C:\\p" + i).value().orElseThrow(), T0.plusSeconds(i));
        }
        ManualExecutor actorExecutor = new ManualExecutor();
        ManualExecutor io = new ManualExecutor();
        FakeScheduler scheduler = new FakeScheduler();
        InMemoryHistoryRepository repository = new InMemoryHistoryRepository();
        repository.load();
        StateActor actor = new StateActor(
                AppState.initial(h).withStore(new HistoryStore.Writable()).withSettings(AppConfig.defaults()),
                actorExecutor);
        new HistorySaver(actor, repository, scheduler, io);
        AppConfig base = AppConfig.defaults();
        AppConfig smaller = new AppConfig(
                base.defaultProfile(),
                base.profiles(),
                base.openPlacement(),
                base.appearance(),
                base.terminal(),
                new HistoryConfig(100, List.of()),
                base.monitor(),
                base.keys());

        // when
        actor.submit(new SettingsLoaded(new LoadedConfig(smaller, List.of())));
        actorExecutor.runAll();
        scheduler.advance(Duration.ofSeconds(1));
        io.runAll();

        // then
        assertThat(repository.saves()).hasSize(1);
        assertThat(repository.saves().getLast().entries()).hasSize(100);
    }
}
