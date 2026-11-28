package tanager.adapter.toolkit.claude;

/**
 * 공급자 안의 한 종류 읽기(스킬 · 커맨드 · 서브에이전트 …). 블로킹 · 예외 없음. 파일은 {@link Collector} 로만 열어 읽은 파일 · 폴더가 지문에
 * 남게 한다. 새 종류 = 읽기 클래스 하나 + {@link ClaudeToolkitProvider} 목록 한 줄.
 */
interface ClaudeReader {
    void read(ClaudeLayout layout, Collector out);
}
