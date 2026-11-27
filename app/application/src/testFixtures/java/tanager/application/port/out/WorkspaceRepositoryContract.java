package tanager.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.WorkspacePath;
import tanager.domain.layout.LayoutNode;
import tanager.domain.layout.Orientation;
import tanager.domain.layout.Ratio;
import tanager.domain.session.ProfileId;
import tanager.domain.shared.PaneId;
import tanager.domain.shared.Result;
import tanager.domain.shared.TabId;
import tanager.domain.workspace.Bounds;
import tanager.domain.workspace.PaneSpec;
import tanager.domain.workspace.PanelState;
import tanager.domain.workspace.Tab;
import tanager.domain.workspace.TabName;
import tanager.domain.workspace.WindowState;
import tanager.domain.workspace.Workspace;
import tanager.domain.workspace.WorkspaceSnapshot;

/**
 * {@link WorkspaceRepository} 계약(ADR-0013 개정, #178). 메모리 Fake 와 JSON 파일 구현이 함께 상속한다. 다음 창 · 탭 번호는 저장하지 않고 읽을
 * 때 계산하므로 왕복하는 스냅샷은 {@link Workspace#restore} 로 만든다.
 */
@Tag("contract")
public abstract class WorkspaceRepositoryContract {

    /** 비어 있는(아직 저장한 적 없는) 저장소. 같은 테스트 안에서 여러 번 불러도 같은 저장소(같은 저장 위치)를 돌려준다. */
    protected abstract WorkspaceRepository repository();

    /** 다음 {@code loadWorkspace} 가 성공하되 {@link StoreAccess#READ_ONLY} 로 열리게 만든다. {@link #repository()} 전후 어느 때나 부를 수 있다. */
    protected abstract void arrangeReadOnly() throws Exception;

    /** 다음 {@code loadWorkspace} 가 오류로 끝나게 만든다. {@link #repository()} 전후 어느 때나 부를 수 있다. */
    protected abstract void arrangeLoadFailure() throws Exception;

    private static WorkspacePath path(ShellEnvironment env, String raw) {
        return WorkspacePath.of(env, raw).value().orElseThrow();
    }

    /** 탭 둘(한글 이름 · 자동 이름) · 분할 · 줌 · 두 환경 · 한글 경로 · 프로파일 없는 창 · 최대화한 창 상태. */
    protected static WorkspaceSnapshot sample() {
        ShellEnvironment ubuntu = new ShellEnvironment.Wsl("Ubuntu-24.04");
        ShellEnvironment windows = new ShellEnvironment.Windows();
        PaneId p1 = new PaneId("p1");
        PaneId p2 = new PaneId("p2");
        PaneId p4 = new PaneId("p4");
        Tab dev = new Tab(
                new TabId("t1"),
                Optional.of(new TabName("개발 서버")),
                new LayoutNode.Split(
                        Orientation.ROW, new Ratio(0.35), new LayoutNode.Leaf(p1), new LayoutNode.Leaf(p2)),
                p2,
                Optional.of(p2));
        Tab infra = new Tab(new TabId("t3"), new LayoutNode.Leaf(p4), p4);
        Workspace workspace = Workspace.restore(
                List.of(dev, infra),
                1,
                Map.of(
                        p1,
                        new PaneSpec(
                                p1,
                                Optional.of(new ProfileId("wsl:Ubuntu-24.04")),
                                Optional.of(path(ubuntu, "/home/me/한글 프로젝트/#1"))),
                        p2,
                        PaneSpec.remembered(p2),
                        p4,
                        new PaneSpec(
                                p4,
                                Optional.of(new ProfileId("powershell")),
                                Optional.of(path(windows, "C:\\work\\팀 폴더")))));
        WindowState window = new WindowState(
                Optional.of(new Bounds(-1600, 40, 1440, 900)),
                true,
                new PanelState(false, 280),
                new PanelState(true, 0));
        return new WorkspaceSnapshot(workspace, window);
    }

    /** 탭 하나 · 창 하나 · 기본 창 상태. */
    protected static WorkspaceSnapshot minimal() {
        return new WorkspaceSnapshot(Workspace.initial(), WindowState.DEFAULT);
    }

    @Test
    @DisplayName("빈 저장소를 읽으면 스냅샷이 없고 쓰기 가능하며 알림이 없다")
    public void loadFromEmptyStoreHasNoSnapshot() {
        // when
        Result<LoadedWorkspace, HistoryStoreError> result = repository().loadWorkspace();

        // then
        assertThat(result.value()).contains(new LoadedWorkspace(Optional.empty(), StoreAccess.WRITABLE, List.of()));
    }

    @Test
    @DisplayName("저장한 스냅샷을 다시 읽으면 탭 · 이름 · 분할 · 줌 · 포커스 · 명세 · 창 상태까지 같다")
    public void saveThenLoadRoundTrips() {
        // given
        WorkspaceRepository repository = repository();
        repository.loadWorkspace();

        // when
        Result<WorkspaceSnapshot, HistoryStoreError> saved = repository.saveWorkspace(sample());
        Result<LoadedWorkspace, HistoryStoreError> loaded = repository.loadWorkspace();

        // then
        assertThat(saved.value()).contains(sample());
        assertThat(loaded.value())
                .contains(new LoadedWorkspace(Optional.of(sample()), StoreAccess.WRITABLE, List.of()));
    }

    @Test
    @DisplayName("두 번 저장하면 나중 스냅샷이 이긴다")
    public void laterSaveWins() {
        // given
        WorkspaceRepository repository = repository();
        repository.loadWorkspace();
        repository.saveWorkspace(sample());

        // when
        repository.saveWorkspace(minimal());

        // then
        assertThat(repository.loadWorkspace().value().orElseThrow().snapshot()).contains(minimal());
    }

    @Test
    @DisplayName("불러오기 전의 저장은 ReadOnly 이고 저장하지 않는다")
    public void saveBeforeLoadIsReadOnly() {
        // given
        WorkspaceRepository repository = repository();

        // when
        Result<WorkspaceSnapshot, HistoryStoreError> saved = repository.saveWorkspace(sample());

        // then
        assertThat(saved.error()).contains(new HistoryStoreError.ReadOnly(HistoryStoreError.ReadOnlyReason.NOT_LOADED));
        assertThat(repository.loadWorkspace().value().orElseThrow().snapshot()).isEmpty();
    }

    @Test
    @DisplayName("READ_ONLY 로 연 뒤의 저장은 ReadOnly 다")
    public void saveAfterReadOnlyLoadIsReadOnly() throws Exception {
        // given
        arrangeReadOnly();
        WorkspaceRepository repository = repository();
        assertThat(repository.loadWorkspace().value().map(LoadedWorkspace::access))
                .contains(StoreAccess.READ_ONLY);

        // when
        Result<WorkspaceSnapshot, HistoryStoreError> saved = repository.saveWorkspace(sample());

        // then
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
    }

    @Test
    @DisplayName("불러오기가 실패한 뒤의 저장은 ReadOnly 다")
    public void saveAfterFailedLoadIsReadOnly() throws Exception {
        // given
        arrangeLoadFailure();
        WorkspaceRepository repository = repository();
        assertThat(repository.loadWorkspace().error()).isPresent();

        // when
        Result<WorkspaceSnapshot, HistoryStoreError> saved = repository.saveWorkspace(sample());

        // then
        assertThat(saved.error()).containsInstanceOf(HistoryStoreError.ReadOnly.class);
    }
}
