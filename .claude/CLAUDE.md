# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트

**Tanager** — 여러 셸(WSL 배포판, Windows PowerShell 5.1, cmd — PowerShell 7 은 범위 밖)을 한 창에서 tmux 처럼 분할해 쓰고, 좌측 사이드바에 지금까지 작업한 경로를 환경별 **폴더 트리**(폴더 아래에 실행 중인 세션)로 보관하는 데스크톱 터미널 관리 프로그램. 재부팅 후에도 레이아웃과 각 창의 마지막 작업 경로를 복원한다. 폴더·세션을 고르면 **도구함 패널**이 그 경로에서 쓸 수 있는 Claude Code 스킬·서브에이전트·CLAUDE.md·MCP·훅·권한을 프로젝트용/전체 공통으로 나눠 보여 준다. 분할·이동·크기 조정은 **키보드와 마우스 모두**로, 상태줄에 CPU·메모리·디스크(WSL VM 포함)를 표시한다. UI 는 둥근 모서리·짧은 전환 효과·눈이 편한 소프트 색(시스템/다크/라이트). Windows 먼저, 이후 macOS.

- 스택: Java 25(최신 LTS, preview 금지) · Gradle 9.9 · Swing + FlatLaf · JediTerm(에뮬레이터) · pty4j(ConPTY/Unix PTY) · Jackson · jpackage
- 설계 문서: `docs/plans/01-프로젝트-구성-설계.html`(개발 체계), `docs/plans/02-SDD.html`(요구사항·설계). 결정 기록: `docs/adr/`
- 상태: **M1 스캐폴딩 완료**(2026-11-24) — Gradle 멀티 모듈 10개 · build-logic · ArchUnit 규칙 · `Result` · `PlatformModule` 포트와 ServiceLoader 선택 · FlatLaf 빈 창 · jpackage(jdeps 축소). 다음은 **M2 단일 터미널(v0.1.0)** — 첫 릴리스. M0 결과는 ADR-0001 · 0003.

## 명령 (M1 스캐폴딩 이후 유효)

```bash
./gradlew check                                   # 포맷 검사 + 전체 테스트 + ArchUnit + 커버리지
./gradlew spotlessApply                           # 포맷 자동 수정 (손으로 맞추지 말 것)
./gradlew :tanager-domain:test                    # 모듈 하나
./gradlew :tanager-domain:test --tests 'dev.tanager.domain.layout.LayoutNodeTest'           # 클래스 하나
./gradlew :tanager-domain:test --tests 'dev.tanager.domain.layout.LayoutNodeTest.split*'    # 메서드 패턴
./gradlew check -PexcludeTags=wsl                 # WSL 통합 테스트 제외 (CI 와 동일)
./gradlew :tanager-app:run                        # 앱 실행
./gradlew :tanager-app:jpackageImage              # 실행 파일 폴더(app-image) 생성 — jdeps 로 모듈 축소(목록: build/jpackage/runtime-modules.txt)
```

### WSL ↔ Windows 실행 구분
저장소는 `/mnt/c/...` 에 있고 Claude 는 WSL 에서 돈다.
- domain / application / ArchUnit 테스트 → WSL 의 `./gradlew` 로 충분.
- ConPTY·PowerShell·wsl.exe 를 쓰는 `tanager-platform-windows`, `tanager-adapter-pty` 테스트와 앱 실행 → **Windows JDK** 로: `cmd.exe /c gradlew.bat <task>`
- 빌드 산출물은 WSL 이 `build/wsl/`, Windows 가 `build/` 로 나뉘어 있어 양쪽을 번갈아 돌려도 된다(리스크 R8). 리포트 경로도 WSL 은 `<모듈>/build/wsl/reports/…`.

### 최초 설정 (클론 직후 1회)
저장소를 처음 만들 때는 사용자가 `.github/scripts/bootstrap-repo.sh <owner/repo>` 를 한 번 실행한다(빈 시작 커밋 · dev · ruleset · 라벨 · 마일스톤).
```bash
git config core.hooksPath .githooks
git config commit.template .gitmessage
git config core.autocrlf false        # 줄바꿈은 .gitattributes 가 결정
```

## 아키텍처 한눈에

헥사고날 + Gradle 멀티 모듈(ADR-0002). 의존 방향과 바운디드 컨텍스트는 `.claude/rules/architecture.md`(자동 로드)에 있다. 여러 파일을 읽어야 보이는 큰 흐름만 적는다.

**cwd 추적 → 이력 → 트리** (ADR-0003)
`tanager-app` 이 플랫폼 셸 통합 스크립트를 `%APPDATA%\Tanager\shell-integration\` 에 풀고 → 세션 기동 시 rc/프로필로 주입 → 셸이 프롬프트마다 OSC 7(bash/zsh) 또는 OSC 9;9(PowerShell)를 출력 → `tanager-adapter-pty` 의 읽기 가상 스레드가 바이트를 `TerminalStream` 구독자들에게 전달 → (1) UI 의 JediTerm 이 화면을 그리고 (2) application 의 `CwdTracker` 가 스트리밍 스캐너로 OSC 를 뽑아 `RecordVisitUseCase` 호출 → `StateActor` 에서 `WorkHistory` 갱신 → 디바운스 저장 + `HistoryChanged` 이벤트 → UI 가 EDT 에서 트리 갱신.

**레이아웃**
`LayoutNode`(sealed: `Split(orientation, ratio, first, second)` | `Leaf(paneId)`)는 불변이며 split/close/resize/swap 은 새 트리를 반환하는 순수 함수다. 포커스 이동은 `LayoutGeometry.rects(tree, bounds)` 로 사각형을 계산해 방향 기준 가장 가까운 창을 고른다. 마우스 조작도 판정(경계선 위치 `dividers`, 놓을 곳 `dropZone`, 드래그 비율 `ratioAt`)은 `LayoutGeometry` 순수 함수가 하고, 키보드와 같은 유스케이스를 부른다. UI 는 트리를 그리고 미리보기만 하며 상태를 갖지 않는다. 드래그 중에는 셸 크기를 바꾸지 않고 놓을 때 한 번만 바꾼다.

**복원**
`state.json`(스키마 버전 포함)에 이력 + 탭/레이아웃 + 창(pane)별 프로파일·마지막 cwd + 창 위치를 저장한다. 시작 시 레이아웃을 그대로 만들고 각 창을 마지막 cwd 에서 새 셸로 띄운다(프로세스 자체는 복원하지 않는다). 경로가 사라졌으면 홈에서 띄우고 알림.

**도구함(toolkit)**
사이드바에서 폴더·세션을 선택 → `QueryToolkitUseCase(WorkspacePath)` → 등록된 `ToolkitProvider` 들(지금은 `tanager-adapter-toolkit-claude` 하나)이 그 경로의 프로젝트 범위(경로와 상위 디렉터리의 `.claude/`, `CLAUDE.md`, `.mcp.json`)와 해당 **환경의 홈**(WSL 세션이면 `\\wsl.localhost\<배포판>\home\<user>\.claude`, Windows 면 `%USERPROFILE%\.claude`)의 사용자 범위를 읽어 `ToolkitItem` 목록을 돌려준다. 읽기 전용이며 비밀값은 마스킹. 경로 변환은 `PlatformModule.paths()` 를 쓴다.

**플랫폼 확장**
OS 차이(셸 탐지, 경로 변환, 앱 디렉터리, 셸 통합, 기동 명령, 리소스 수집, 다크/라이트·애니메이션 설정 감지)는 전부 application 의 `PlatformModule` 포트 뒤에 있다. `tanager-app` 이 `ServiceLoader` 로 현재 OS 구현 하나를 고른다. macOS 추가 시 다른 모듈을 바꾸게 된다면 설계 결함이다.

## 작업 방식

- 설계: DDD(전략적 설계 + 전술 패턴 일부), 구현: TDD, 코드: Functional Core / Imperative Shell. 세부 규칙은 `.claude/rules/` (Java 파일·테스트·플랫폼 파일을 열 때 해당 규칙이 자동 로드됨).
- 기능 구현은 `/tdd [이슈번호] <설명>` — (이슈 없으면 `/issue`) → architect → test-author → implementer → reviewer → `/pr`.
- **브랜치**: `dev` 는 통합 브랜치(작업 브랜치 → dev squash), `main` 은 릴리스 전용이자 GitHub 기본 브랜치(dev → main merge commit + 태그). PR 은 항상 `--base` 를 명시한다(GitHub 화면의 기본 base 는 main). 긴급 수정만 `hotfix/*` → main.
- **역할**: 이슈·브랜치·커밋·푸시·PR·CI 대응·리뷰 코멘트, 그리고 **작업 PR 의 dev squash 병합**(CI green · 차단 0건일 때)은 Claude. **사용자는 마일스톤마다 릴리스 PR(dev → main)에서 한 번 확인**하고 main 병합을 결정한다. hotfix → main · 역병합 · PR 닫기도 사용자. 릴리스 태그는 사용자 승인 후 Claude. 세부는 `.claude/rules/git.md`.
- 커밋 `/commit`, 이슈 `/issue`, PR `/pr`, 릴리스 `/release`, 결정 기록 `/adr`. 마일스톤은 `.github/milestones.json`(SDD §13.1)이 원본.
- 여러 모듈을 동시에 바꾸는 큰 기능은 에이전트 팀을 쓸 수 있다(실험 기능, 기본 꺼짐). 켜는 법과 역할 분담은 `docs/plans/01-프로젝트-구성-설계.html` §11.

## 함정

- `wsl.exe --list --quiet` 출력은 UTF-16LE. `WSL_UTF8=1` 을 주거나 UTF-16LE 로 디코딩.
- JediTerm 은 Maven Central 에 없다 → `https://packages.jetbrains.team/maven/p/ij/intellij-dependencies` 저장소. 글자 단위 대체 글꼴이 없고 IME 조합 중 글자는 기본 글꼴로만 그리므로 **터미널 기본 글꼴은 한글 글리프가 있어야 한다**(D2Coding 번들).
- Java 25 + JNA(pty4j): 실행 옵션에 `--enable-native-access=ALL-UNNAMED`.
- Gradle 데몬도 JDK 25 로 돈다(`gradle/gradle-daemon-jvm.properties`, 없으면 자동으로 받음). 데몬 JDK 가 낮으면 Spotless(palantir)가 최신 문법(record 패턴 등)을 못 읽어 OS 마다 결과가 달라진다 — 이 파일을 지우지 말 것.
- WSL 에서 source 되는 `*.sh` 가 CRLF 면 `$'\r': command not found`. `.gitattributes` 를 지키고 Windows 편집기에서 줄바꿈을 바꾸지 말 것.
- Swing 컴포넌트는 EDT 에서만. 도메인 상태는 `StateActor` 밖에서 바꾸지 않는다.
- 경로를 `String` 으로 비교하지 않는다. Windows 는 대소문자 무시, WSL/macOS 는 구분 → `WorkspacePath`.
- 저장 파일 스키마를 바꾸면 `schemaVersion` 을 올리고 이전 버전 픽스처로 마이그레이션 테스트를 추가.
