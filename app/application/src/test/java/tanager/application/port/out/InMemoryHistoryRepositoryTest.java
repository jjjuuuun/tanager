package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tanager.domain.history.HistoryPolicy;
import tanager.domain.history.WorkHistory;
import tanager.domain.shared.Result;

/** 계약 밖에서 Fake 만 재현하는 경우 — 지정한 load 결과에 따른 저장 거부. */
class InMemoryHistoryRepositoryTest {
    private static final WorkHistory EMPTY = WorkHistory.empty(HistoryPolicy.DEFAULT);

    @Test
    @DisplayName("READ_ONLY 로 불러온 뒤의 저장은 ReadOnly 이고 호출은 기록한다")
    void saveAfterReadOnlyLoadIsReadOnly() {
        // given
        InMemoryHistoryRepository repository = new InMemoryHistoryRepository()
                .willLoad(Result.ok(new LoadedHistory(EMPTY, StoreAccess.READ_ONLY, List.of())));
        repository.load();

        // when
        Result<WorkHistory, HistoryStoreError> saved = repository.save(HistoryRepositoryContract.sample());

        // then
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
        assertThat(repository.saves()).containsExactly(HistoryRepositoryContract.sample());
    }

    @Test
    @DisplayName("불러오기가 실패한 뒤의 저장은 ReadOnly 다")
    void saveAfterFailedLoadIsReadOnly() {
        // given
        InMemoryHistoryRepository repository =
                new InMemoryHistoryRepository().willLoad(Result.err(new HistoryStoreError.Io("디스크 없음")));
        repository.load();

        // when
        Result<WorkHistory, HistoryStoreError> saved = repository.save(HistoryRepositoryContract.sample());

        // then
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
    }

    @Test
    @DisplayName("WRITABLE 로 다시 불러오면 다시 저장할 수 있다")
    void writableReloadAllowsSaveAgain() {
        // given
        InMemoryHistoryRepository repository = new InMemoryHistoryRepository()
                .willLoad(Result.ok(new LoadedHistory(EMPTY, StoreAccess.READ_ONLY, List.of())));
        repository.load();
        repository.willLoad(Result.ok(new LoadedHistory(EMPTY, StoreAccess.WRITABLE, List.of())));
        repository.load();

        // when
        Result<WorkHistory, HistoryStoreError> saved = repository.save(HistoryRepositoryContract.sample());

        // then
        assertThat(saved.value()).contains(HistoryRepositoryContract.sample());
    }
}
