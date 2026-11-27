package tanager.ui.sidebar;

import java.util.Objects;
import java.util.Optional;
import tanager.domain.session.ProfileId;

/**
 * 메뉴 한 줄: 동작 · 글자 · 활성 여부.
 *
 * @param profile "다른 프로파일로 열기"의 셸(#91). 다른 동작은 비어 있다
 */
record MenuItem(MenuAction action, String label, boolean enabled, Optional<ProfileId> profile) implements MenuEntry {
    MenuItem {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(profile, "profile");
    }

    MenuItem(MenuAction action, String label, boolean enabled) {
        this(action, label, enabled, Optional.empty());
    }
}
