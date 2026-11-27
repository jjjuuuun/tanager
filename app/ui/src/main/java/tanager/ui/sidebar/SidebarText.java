package tanager.ui.sidebar;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import tanager.domain.history.Alias;
import tanager.domain.history.SessionLeaf;
import tanager.domain.history.SessionStatus;
import tanager.domain.history.ShellEnvironment;
import tanager.domain.history.VisitedDirectory;
import tanager.domain.history.WorkspacePath;
import tanager.ui.i18n.Msg;

/** 사이드바 노드의 글자 · 툴팁(순수 계산, #84). */
final class SidebarText {
    static final String PINNED_SECTION = Msg.SIDEBAR_PINNED.text();
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private SidebarText() {}

    /** "WSL · 배포판" 또는 "Windows". */
    static String environment(ShellEnvironment env) {
        return switch (env) {
            case ShellEnvironment.Wsl(String distro) -> "WSL · " + distro;
            case ShellEnvironment.Windows _ -> "Windows";
        };
    }

    /** "#1 bash", 끝났으면 "#1 bash · 종료 3". */
    static String session(SessionLeaf session) {
        String name = "#" + session.number() + " " + session.shellName();
        return switch (session.status()) {
            case SessionStatus.Running _ -> name;
            case SessionStatus.Exited(int code) -> Msg.SIDEBAR_SESSION_EXITED.text(name, code);
        };
    }

    /** 전체 경로, 방문 기록이 있으면 " · 방문 N회 · 최근 날짜 시각"(현지 시각). */
    static String folderTooltip(WorkspacePath path, Optional<VisitedDirectory> visit, ZoneId zone) {
        return visit.map(v -> Msg.SIDEBAR_FOLDER_VISITS.text(
                        path.path(), v.visitCount(), WHEN.format(v.lastVisited().atZone(zone))))
                .orElse(path.path());
    }

    /** {@link #folderTooltip(WorkspacePath, Optional, ZoneId)} 에, 사라진 경로면 " · 이 경로가 더 이상 없다"를 붙인다(#186). */
    static String folderTooltip(WorkspacePath path, Optional<VisitedDirectory> visit, ZoneId zone, boolean missing) {
        String tooltip = folderTooltip(path, visit, zone);
        return missing ? Msg.SIDEBAR_FOLDER_MISSING.text(tooltip) : tooltip;
    }

    /** 별칭(보조 글자). */
    static Optional<String> secondary(Optional<Alias> alias) {
        return alias.map(Alias::value);
    }
}
