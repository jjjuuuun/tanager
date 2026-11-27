package tanager.ui.keys;

/** 키에 묶을 수 있는 동작 하나(SDD §8.2). 설정 파일에서는 {@link ActionIds} 의 문자열 id 로 부른다(FR-CFG-01). */
public sealed interface KeyAction
        permits LayoutAction, KeyAction.ToggleSidebar, KeyAction.SearchTree, KeyAction.OpenSettings {

    /** 사이드바 보이기 · 숨기기(FR-UI-04). */
    record ToggleSidebar() implements KeyAction {}

    /** 사이드바를 보이고 트리 검색 칸으로 간다. */
    record SearchTree() implements KeyAction {}

    /** 설정 파일을 OS 기본 편집기로 연다(FR-CFG-03). */
    record OpenSettings() implements KeyAction {}
}
