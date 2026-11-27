package tanager.adapter.persistence;

import java.util.ArrayList;
import java.util.List;
import tanager.application.port.out.WorkspaceLoadNotice;
import tools.jackson.databind.node.ObjectNode;

/** state.json 마이그레이션 체인(SDD §9.4). 버전마다 {@link Migration} 하나를 더하고 {@link #CURRENT} 를 올린다. */
final class Migrations {
    /** 이 앱이 쓰는 schemaVersion. */
    static final long CURRENT = 2;

    private static final List<Migration> CHAIN = List.of(new MigrationV1ToV2());

    private Migrations() {}

    /**
     * {@code version}(1 이상 {@link #CURRENT} 이하)의 {@code root} 를 현재 모양으로 올린다. 올렸으면 {@code Migrated(version, CURRENT)} 를 먼저
     * 알린다. 이미 현재 버전이면 그대로다. 순수.
     */
    static Migration.Step upgrade(ObjectNode root, long version) {
        if (version >= CURRENT) {
            return new Migration.Step(root, List.of());
        }
        List<WorkspaceLoadNotice> notices = new ArrayList<>();
        notices.add(new WorkspaceLoadNotice.Migrated(version, CURRENT));
        ObjectNode current = root;
        for (Migration migration : CHAIN) {
            if (migration.from() >= version) {
                Migration.Step step = migration.apply(current);
                current = step.root();
                notices.addAll(step.notices());
            }
        }
        return new Migration.Step(current, notices);
    }
}
