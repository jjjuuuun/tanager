package dev.tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 이력 읽기 알림이 사용자에게 보여 줄 한 줄 설명. */
class HistoryLoadNoticeTest {

    @Test
    @DisplayName("백업에서 복구했고 손상 원본을 옮겼으면 옮긴 파일 이름을 함께 알린다")
    void recoveredFromBackupWithMovedOriginalNamesTheFile() {
        // given
        HistoryLoadNotice notice =
                new HistoryLoadNotice.RecoveredFromBackup(Optional.of("state.json.corrupt-20261126T010203Z"));

        // when
        String text = notice.describe();

        // then
        assertThat(text).isEqualTo("이력 파일이 손상돼 백업에서 복구했다(손상 파일은 state.json.corrupt-20261126T010203Z 로 복사해 두었다)");
    }

    @Test
    @DisplayName("백업에서 복구했지만 손상 원본을 옮기지 않았으면 파일 이름 없이 알린다")
    void recoveredFromBackupWithoutMoveOmitsFileName() {
        // given
        HistoryLoadNotice notice = new HistoryLoadNotice.RecoveredFromBackup(Optional.empty());

        // when
        String text = notice.describe();

        // then
        assertThat(text).isEqualTo("이력 파일이 손상돼 백업에서 복구했다");
    }

    @Test
    @DisplayName("손상 원본을 옮기고 빈 이력으로 시작했으면 옮긴 파일 이름을 알린다")
    void corruptMovedAsideNamesTheFile() {
        // given
        HistoryLoadNotice notice = new HistoryLoadNotice.CorruptMovedAside("state.json.corrupt-20261126T010203Z");

        // when
        String text = notice.describe();

        // then
        assertThat(text).isEqualTo("이력 파일이 손상돼 state.json.corrupt-20261126T010203Z 로 옮기고 빈 이력으로 시작했다");
    }

    @Test
    @DisplayName("건너뛴 항목 수를 알린다")
    void entriesSkippedTellsCount() {
        // given
        HistoryLoadNotice notice = new HistoryLoadNotice.EntriesSkipped(3);

        // when
        String text = notice.describe();

        // then
        assertThat(text).isEqualTo("이력 항목 3 개를 읽지 못해 건너뛰었다");
    }

    @Test
    @DisplayName("건너뛴 항목 수는 1 이상이어야 한다")
    void entriesSkippedRejectsZero() {
        assertThatThrownBy(() -> new HistoryLoadNotice.EntriesSkipped(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상위 버전 알림은 int 범위를 넘는 schemaVersion 도 그대로 보여 준다")
    void newerSchemaShowsVersionBeyondIntRange() {
        // given
        HistoryLoadNotice notice = new HistoryLoadNotice.NewerSchema(3_000_000_000L);

        // when
        String text = notice.describe();

        // then
        assertThat(text).isEqualTo("새 버전(schemaVersion 3000000000)이 쓴 이력 파일이라 읽기만 한다");
    }

    @Test
    @DisplayName("잠금 파일을 열 수 없으면 그 이유를 붙여 읽기 전용임을 알린다")
    void lockUnavailableIncludesDetail() {
        // given
        HistoryLoadNotice notice = new HistoryLoadNotice.LockUnavailable("state.lock: 권한 없음");

        // when
        String text = notice.describe();

        // then
        assertThat(text).isEqualTo("이력 잠금 파일을 열 수 없어 읽기만 한다: state.lock: 권한 없음");
    }

    @Test
    @DisplayName("다른 인스턴스가 쓰고 있으면 읽기 전용임을 알린다")
    void lockedByOtherInstanceTellsReadOnly() {
        // given
        HistoryLoadNotice notice = new HistoryLoadNotice.LockedByOtherInstance();

        // when
        String text = notice.describe();

        // then
        assertThat(text).isEqualTo("다른 Tanager 가 이력 파일을 쓰고 있어 읽기만 한다");
    }
}
