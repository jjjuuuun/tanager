package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tanager.domain.history.Alias;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkHistory;
import tanager.domain.history.WorkspacePath;
import tanager.domain.shared.Result;

/**
 * {@link HistoryRepository} 계약(ADR-0013). 메모리 Fake 와 JSON 파일 구현이 함께 상속한다. 저장소는 {@link
 * HistoryPolicy#DEFAULT} 로 이력을 만든다.
 */
@Tag("contract")
public abstract class HistoryRepositoryContract {

    /** 비어 있는(아직 저장한 적 없는) 저장소. 같은 테스트 안에서 여러 번 불러도 같은 저장소(같은 저장 위치)를 돌려준다. */
    protected abstract HistoryRepository repository();

    /** 다음 {@code load} 가 성공하되 {@link StoreAccess#READ_ONLY} 로 열리게 만든다(상위 버전 파일 등). {@link #repository()} 전후 어느 때나 부를 수 있다. */
    protected abstract void arrangeReadOnly() throws Exception;

    /** 다음 {@code load} 가 오류로 끝나게 만든다(저장 위치를 읽을 수 없음 등). {@link #repository()} 전후 어느 때나 부를 수 있다. */
    protected abstract void arrangeLoadFailure() throws Exception;

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    /** 별칭 · 고정 · 숨김 · 두 환경 · UNC · 한글 · 공백 · '#' 경로를 고루 담은 이력. */
    protected static WorkHistory sample() {
        Instant t0 = Instant.parse("2026-11-20T09:12:03Z");
        Instant t1 = Instant.parse("2026-11-24T11:05:41Z");
        ShellEnvironment ubuntu = new ShellEnvironment.Wsl("Ubuntu-24.04");
        ShellEnvironment windows = new ShellEnvironment.Windows();
        return WorkHistory.restore(
                List.of(
                        new VisitedDirectory(
                                path(ubuntu, "/home/me/한글 프로젝트/#1"),
                                t0,
                                t1,
                                37,
                                true,
                                Optional.of(new Alias("내 프로젝트")),
                                false),
                        new VisitedDirectory(
                                path(windows, "C:\\work\\infra"), t0, t0, 1, false, Optional.empty(), true),
                        new VisitedDirectory(
                                path(windows, "\\\\srv\\share\\팀 폴더"), t0, t1, 5, false, Optional.empty(), false)),
                HistoryPolicy.DEFAULT);
    }

    @Test
    @DisplayName("빈 저장소를 읽으면 빈 이력이고 쓰기 가능하며 알림이 없다")
    public void loadFromEmptyStoreReturnsEmptyWritableHistory() {
        // when
        Result<LoadedHistory, HistoryStoreError> result = repository().load();

        // then
        assertThat(result.value())
                .contains(new LoadedHistory(WorkHistory.empty(HistoryPolicy.DEFAULT), StoreAccess.WRITABLE, List.of()));
    }

    @Test
    @DisplayName("저장한 이력을 다시 읽으면 별칭 · 고정 · 숨김 · 한글 · UNC 경로까지 같은 이력이다")
    public void saveThenLoadRoundTrips() {
        // given
        HistoryRepository repository = repository();
        repository.load();

        // when
        Result<WorkHistory, HistoryStoreError> saved = repository.save(sample());
        Result<LoadedHistory, HistoryStoreError> loaded = repository.load();

        // then
        assertThat(saved.value()).contains(sample());
        assertThat(loaded.value()).contains(new LoadedHistory(sample(), StoreAccess.WRITABLE, List.of()));
    }

    @Test
    @DisplayName("두 번 저장하면 나중 이력이 이긴다")
    public void laterSaveWins() {
        // given
        HistoryRepository repository = repository();
        repository.load();
        repository.save(sample());

        // when
        repository.save(WorkHistory.empty(HistoryPolicy.DEFAULT));

        // then
        assertThat(repository.load().value().orElseThrow().history())
                .isEqualTo(WorkHistory.empty(HistoryPolicy.DEFAULT));
    }

    @Test
    @DisplayName("불러오기 전의 저장은 ReadOnly 이고 저장하지 않는다")
    public void saveBeforeLoadIsReadOnly() {
        // given
        HistoryRepository repository = repository();

        // when
        Result<WorkHistory, HistoryStoreError> saved = repository.save(sample());

        // then
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
        assertThat(repository.load().value().orElseThrow().history())
                .isEqualTo(WorkHistory.empty(HistoryPolicy.DEFAULT));
    }

    @Test
    @DisplayName("READ_ONLY 로 연 뒤의 저장은 ReadOnly 다")
    public void saveAfterReadOnlyLoadIsReadOnly() throws Exception {
        // given
        arrangeReadOnly();
        HistoryRepository repository = repository();
        assertThat(repository.load().value().map(LoadedHistory::access)).contains(StoreAccess.READ_ONLY);

        // when
        Result<WorkHistory, HistoryStoreError> saved = repository.save(sample());

        // then
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
    }

    @Test
    @DisplayName("불러오기가 실패한 뒤의 저장은 ReadOnly 다")
    public void saveAfterFailedLoadIsReadOnly() throws Exception {
        // given
        arrangeLoadFailure();
        HistoryRepository repository = repository();
        assertThat(repository.load().error()).isPresent();

        // when
        Result<WorkHistory, HistoryStoreError> saved = repository.save(sample());

        // then
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
    }
}
