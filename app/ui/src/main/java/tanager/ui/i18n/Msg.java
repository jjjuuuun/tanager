package tanager.ui.i18n;

/** 화면 글자 키. 문장은 번들 {@code tanager/ui/i18n/messages*.properties} 에 있다(#136, ADR-0017). */
public enum Msg {
    PANE_STARTING("pane.starting"),
    PANE_HINT_EXITED("pane.hint.exited"),
    PANE_HINT_RETRY("pane.hint.retry"),
    HINT_ESC_CLOSE("hint.esc.close"),
    HINT_ESC_BACK("hint.esc.back"),
    PANE_PICKING_HEADER("pane.picking.header"),
    PANE_NO_INTEGRATION("pane.noIntegration"),
    PANE_EXIT_CODE("pane.exitCode"),
    PANE_ERRORED("pane.errored"),
    PANE_LAUNCH_FAILED("pane.launchFailed"),
    PANE_NO_SHELL("pane.noShell"),
    PANE_REJECTED("pane.rejected"),
    PANE_REJECTED_TRANSITION("pane.rejected.transition"),
    PANE_REJECTED_ENVIRONMENT("pane.rejected.environment"),
    PICKER_TITLE("picker.title"),
    PICKER_REMEMBER("picker.remember"),
    PICKER_KEYS("picker.keys"),
    PICKER_MISSING("picker.missing"),
    SIDEBAR_PINNED("sidebar.pinned"),
    SIDEBAR_SESSION_EXITED("sidebar.session.exited"),
    SIDEBAR_FOLDER_VISITS("sidebar.folder.visits"),
    SIDEBAR_NO_MATCH("sidebar.noMatch"),
    SIDEBAR_SEARCH_PLACEHOLDER("sidebar.search.placeholder"),
    MENU_COPY_PATH("menu.copyPath"),
    MENU_PIN("menu.pin"),
    MENU_UNPIN("menu.unpin"),
    MENU_ALIAS("menu.alias"),
    MENU_HIDE("menu.hide"),
    MENU_UNHIDE("menu.unhide"),
    MENU_FORGET("menu.forget"),
    MENU_SHOW_HIDDEN("menu.showHidden"),
    MENU_CONCEAL_HIDDEN("menu.concealHidden"),
    MENU_EXPAND_ALL("menu.expandAll"),
    MENU_COLLAPSE_ALL("menu.collapseAll"),
    FORGET_CONFIRM("forget.confirm"),
    FORGET_TITLE("forget.title"),
    ALIAS_TITLE("alias.title"),
    ALIAS_HINT("alias.hint"),
    TERMINAL_COPY("terminal.copy"),
    TERMINAL_PASTE("terminal.paste"),
    TERMINAL_FIND("terminal.find"),
    TERMINAL_CLEAR("terminal.clear"),
    TERMINAL_PAGE_UP("terminal.pageUp"),
    TERMINAL_PAGE_DOWN("terminal.pageDown"),
    TERMINAL_LINE_UP("terminal.lineUp"),
    TERMINAL_LINE_DOWN("terminal.lineDown"),
    TERMINAL_SELECT_ALL("terminal.selectAll"),
    APP_FONT_FALLBACK("app.fontFallback"),
    APP_THEME_FAILED("app.themeFailed"),
    APP_CANNOT_START("app.cannotStart");

    private final String key;

    Msg(String key) {
        this.key = key;
    }

    /** 번들의 키. */
    public String key() {
        return key;
    }

    /** 앱의 화면 언어로 쓴 문장. {@code args} 는 문장의 {0}, {1} … 자리에 들어간다. */
    public String text(Object... args) {
        return Messages.DEFAULT.text(this, args);
    }
}
