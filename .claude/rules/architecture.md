# 아키텍처 규칙

전체 설계: `docs/plans/02-SDD.html`. 결정 기록: `docs/adr/`.

## 계층과 의존 방향 (ArchUnit 이 `tanager-architecture-test` 에서 강제)
```
tanager-domain            ← 순수 Java. 외부 라이브러리·I/O·Swing·스레드 금지
   ↑
tanager-application       ← 유스케이스(port.in) + 출력 포트(port.out). domain 만 의존
   ↑
tanager-adapter-pty  tanager-adapter-persistence  tanager-adapter-toolkit-claude
tanager-platform-windows  tanager-platform-macos  tanager-ui-swing
   ↑                       (서로 의존 금지. application 의 포트만 구현/사용)
tanager-app               ← 조립 루트(composition root). 유일하게 모든 모듈을 안다
```
- UI 는 pty4j 를 직접 쓰지 않는다. `TerminalStream` 포트를 JediTerm `TtyConnector` 로 감싼다.
- OS 분기(`if windows`)는 `tanager-app` 의 플랫폼 선택(ServiceLoader) 한 곳에만 있다. 나머지 코드는 `PlatformModule` 포트만 본다(셸·경로·셸 통합·리소스 `resources()`·외관 `appearance()`).
- UI 의 색·모서리·전환 시간은 테마 토큰(`themes/soft-*.properties`, `TerminalPalette`)으로만 쓴다. 코드에 색 리터럴 금지 — 대비는 `ThemeContrastTest` 가 검사.
- 새 OS 지원 = `tanager-platform-<os>` 모듈 추가 + `META-INF/services` 등록. 다른 모듈은 바꾸지 않는다.
- 새 AI 도구 지원(Codex 등) = `tanager-adapter-toolkit-<도구>` 모듈 추가 + `ToolkitProvider` 등록. 같은 원칙.
- toolkit 어댑터는 사용자 설정 파일을 **절대 쓰지 않는다**. 비밀값(MCP `env`, 헤더, 토큰)은 읽는 즉시 마스킹하고 도메인으로 넘기지 않는다.

## 바운디드 컨텍스트 (domain 패키지)
- `history` — 작업 경로 이력(핵심 도메인). 애그리거트 `WorkHistory`, 값 객체 `WorkspacePath`, `ShellEnvironment`
- `layout` — 분할 레이아웃. 불변 트리 `LayoutNode = Split | Leaf`, 순수 함수 연산
- `session` — 터미널 세션·셸 프로파일. 상태 `Starting → Running → Exited`
- `workspace` — 탭·창 스냅샷(복원 단위)
- `toolkit` — 경로별로 쓸 수 있는 AI 에이전트 구성(스킬·서브에이전트·메모리·MCP·훅·권한)의 **읽기 전용** 카탈로그. 공급자 SPI(`ToolkitProvider`)로 Claude Code 외 도구도 추가 가능
- `monitor` — CPU·메모리·디스크 표본과 임계값 판정(저장하지 않는 일시 상태, StateActor 를 거치지 않는다)
- `shared` — `Result`, ID 타입, 도메인 이벤트

## 스레드 모델
- Swing 은 EDT 에서만 만진다. 다른 스레드에서는 `UiDispatcher` 포트를 통한다.
- PTY 읽기는 세션당 가상 스레드 1개.
- 애플리케이션 상태 변경은 단일 스레드 `StateActor` 에 직렬화 → 락 없이 도메인 객체를 다룬다.
- 저장은 디바운스(1초) + 원자적 쓰기(tmp → ATOMIC_MOVE).
