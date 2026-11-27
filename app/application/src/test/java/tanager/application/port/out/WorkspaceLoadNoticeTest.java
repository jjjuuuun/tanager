package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 작업 공간 읽기 알림이 사용자에게 보여 줄 한 줄 설명과 불변식(#178). */
class WorkspaceLoadNoticeTest {

    @Test
    @DisplayName("버린 부분과 까닭을 알린다")
    void partDiscardedTellsPartAndReason() {
        // given
        WorkspaceLoadNotice notice = new WorkspaceLoadNotice.PartDiscarded("workspace", "객체가 아니다");

        // when
        String text = notice.describe();

        // then
        assertThat(text).isEqualTo("저장한 workspace 를 읽지 못해 버렸다: 객체가 아니다");
    }

    @Test
    @DisplayName("뺀 창 수를 알린다")
    void panesDroppedTellsCount() {
        // given
        WorkspaceLoadNotice notice = new WorkspaceLoadNotice.PanesDropped(2);

        // when
        String text = notice.describe();

        // then
        assertThat(text).isEqualTo("저장한 창 2 개를 읽지 못해 빼고 복원했다");
    }

    @Test
    @DisplayName("옮긴 버전을 알린다")
    void migratedTellsVersions() {
        // given
        WorkspaceLoadNotice notice = new WorkspaceLoadNotice.Migrated(1, 2);

        // when
        String text = notice.describe();

        // then
        assertThat(text).isEqualTo("상태 파일을 schemaVersion 1 에서 2 형식으로 읽었다");
    }

    @Test
    @DisplayName("불변식: 뺀 창은 1 개 이상, 옮긴 버전은 올라간다, 부분 · 까닭은 null 이 아니다")
    void invariants() {
        // then
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkspaceLoadNotice.PanesDropped(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new WorkspaceLoadNotice.Migrated(2, 2));
        assertThatNullPointerException().isThrownBy(() -> new WorkspaceLoadNotice.PartDiscarded(null, "x"));
        assertThatNullPointerException().isThrownBy(() -> new WorkspaceLoadNotice.PartDiscarded("window", null));
    }

    @Test
    @DisplayName("읽은 작업 공간은 알림 목록을 복사해 갖고 null 을 받지 않는다")
    void loadedWorkspaceCopiesNotices() {
        // given
        List<WorkspaceLoadNotice> notices = new ArrayList<>(List.of(new WorkspaceLoadNotice.PanesDropped(1)));

        // when
        LoadedWorkspace loaded = new LoadedWorkspace(Optional.empty(), StoreAccess.WRITABLE, notices);
        notices.clear();

        // then
        assertThat(loaded.notices()).containsExactly(new WorkspaceLoadNotice.PanesDropped(1));
        assertThatNullPointerException().isThrownBy(() -> new LoadedWorkspace(null, StoreAccess.WRITABLE, List.of()));
        assertThatNullPointerException().isThrownBy(() -> new LoadedWorkspace(Optional.empty(), null, List.of()));
    }
}
