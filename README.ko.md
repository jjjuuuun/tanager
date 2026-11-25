# Tanager

[English](README.md) | **한국어**

[![CI](https://github.com/jjjuuuun/tanager/actions/workflows/ci.yml/badge.svg?branch=dev)](https://github.com/jjjuuuun/tanager/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/jjjuuuun/tanager?include_prereleases)](https://github.com/jjjuuuun/tanager/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Windows 용 데스크톱 터미널 관리 프로그램입니다. **WSL**, **Windows PowerShell 5.1**, **cmd** 터미널을 tmux 처럼 한 창에서 나눠 쓰고, 지금까지 작업한 모든 경로를 **폴더 트리**로 남겨 둡니다. 컴퓨터를 다시 켜도 어디서 무엇을 하고 있었는지 바로 알 수 있습니다.

> [!IMPORTANT]
> **v0.1.0 은 골격(스캐폴딩) 릴리스입니다.** 빈 창이 하나 뜨고, 빌드 · 테스트 · 패키징 과정이 처음부터 끝까지 동작하는지 확인하는 버전입니다. 실제 터미널은 **v0.2.0** 부터 들어갑니다. [로드맵](#로드맵)을 보세요.

## 왜 만들었나

WSL 에서 Claude Code 같은 작업을 오래 하다가 컴퓨터를 끄면, 어느 프로젝트의 어느 폴더에서 어떤 셸을 쓰고 있었는지 다시 찾아야 합니다. Tanager 는 환경별로 작업한 경로를 모두 기억해 왼쪽에 트리로 보여 주고, 다시 열면 나눠 둔 화면과 각 창의 마지막 경로를 그대로 되살립니다.

## 기능

| 기능 | 들어가는 버전 |
|---|---|
| WSL 배포판 · Windows PowerShell 5.1 · cmd 를 한 창에서 (자동 탐지) | v0.2.0 |
| 눈이 편한 소프트 테마(둥근 모서리 · 짧은 전환 효과), 한글이 되는 터미널 글꼴(D2Coding) | v0.2.0 |
| 환경별 폴더 → 세션 트리(방문한 모든 경로), 고정 · 별칭 · 검색 | v0.3.0 |
| tmux 식 분할 — 나누기 · 크기 조정 · 이동 · 교환 · 줌 · 닫기를 **키보드와 마우스 모두로** | v0.4.0 |
| 다시 시작해도 탭 · 분할 · 각 창의 마지막 경로 복원 | v0.5.0 |
| 시스템 / 다크 / 라이트 테마, 상태줄의 CPU · 메모리 · 디스크(WSL VM 포함) | v0.5.0 |
| 도구함 패널: 고른 폴더에서 쓸 수 있는 Claude Code 스킬 · 서브에이전트 · `CLAUDE.md` · MCP 서버 · 훅 · 권한 | v1.0.0 |
| macOS | v1.1.0 |

PowerShell 7(`pwsh`)과 사용자 정의 셸 프로파일은 일부러 지원 범위에서 뺐습니다.

## 설치

**필요한 것:** Windows 10 또는 11 (x64, Windows 11 에서 확인). Java 는 설치하지 않아도 됩니다 — 필요한 만큼 줄인 Java 런타임이 함께 들어 있습니다.

[최신 릴리스](https://github.com/jjjuuuun/tanager/releases)에서 둘 중 하나를 받습니다. 어느 쪽도 관리자 권한이 필요 없습니다.

- **설치 파일** `Tanager-<버전>-windows-x64.msi` — 실행하면 현재 사용자용으로 `%LOCALAPPDATA%\Tanager` 에 설치되고 시작 메뉴에 **Tanager** 가 생깁니다. 지우려면 **설정 → 앱** 에서 Tanager 를 제거합니다.
- **압축 파일** `Tanager-<버전>-windows-x64.zip` — 원하는 곳에 풀고 `Tanager\Tanager.exe` 를 실행합니다. 지우려면 폴더를 삭제합니다.

아직 코드 서명이 없어 Windows SmartScreen 경고가 뜰 수 있습니다. **추가 정보 → 실행**을 누르세요.

## 사용 방법

### v0.1.0 에서 되는 것

`Tanager.exe` 를 실행하면 제목이 **"Tanager — windows"** 인 창이 뜹니다. 제목은 Windows 플랫폼 모듈을 제대로 찾았다는 뜻입니다. 아직 그 밖의 기능은 없습니다.

### 예정 (v0.2.0 부터)

기본 키 바인딩(Windows)입니다. 모든 동작은 마우스로도 할 수 있습니다 — 창 머리줄 버튼, 경계선 드래그, 창이나 사이드바 폴더를 끌어다 놓기. v1.0 전까지 바뀔 수 있습니다.

| 동작 | 키 | 마우스 |
|---|---|---|
| 오른쪽 / 아래로 분할 | <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>=</kbd> / <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>-</kbd> | 창 머리줄 ▥ / ▤ |
| 포커스 이동 | <kbd>Alt</kbd>+<kbd>←↑→↓</kbd> | 창 클릭 |
| 크기 조정(5 %) | <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>←↑→↓</kbd> | 경계선 드래그(더블클릭 = 50:50) |
| 줌 | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>Z</kbd> | 머리줄 □ 또는 머리줄 더블클릭 |
| 창 닫기 | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>W</kbd> | 머리줄 × |
| 창 이동 / 교환 | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>←→</kbd>(교환) | 머리줄 ≡ 를 다른 창의 가장자리(이동) · 가운데(교환)로 끌기 |
| 새 탭 / 탭 전환 | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>T</kbd> / <kbd>Ctrl</kbd>+<kbd>Tab</kbd> | 탭 막대 |
| 이력의 폴더 열기 | 사이드바에서 <kbd>Enter</kbd> | 더블클릭, 또는 창 위로 끌어다 놓기 |
| 사이드바 / 도구함 토글 | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>E</kbd> / <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>K</kbd> | — |
| 폴더 트리 검색 | <kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>F</kbd> | — |

Tanager 는 셸 통합 스크립트가 프롬프트마다 알려 주는 현재 경로(bash 는 OSC 7, PowerShell · cmd 는 OSC 9;9)로 각 셸의 위치를 압니다. 입력한 명령이나 출력은 기록하지 않고 **경로만** 남깁니다.

## 소스에서 빌드

**필요한 것**

- Git
- Gradle 을 띄울 JDK 17 이상(아무 배포판이나, `PATH` 에). 프로젝트 자체는 **Java 25** 로 빌드하며, 없으면 Gradle 이 자동으로 내려받습니다.
- 앱 실행과 패키지 빌드는 Windows. 핵심 모듈과 테스트는 Linux / WSL 에서도 됩니다.

```bash
git clone https://github.com/jjjuuuun/tanager.git
cd tanager
git switch dev                         # 개발은 dev 에서, main 은 최신 릴리스

./gradlew check                      # 포맷 검사 + 전체 테스트 + 아키텍처 규칙 + 커버리지
./gradlew :bootstrap:run             # 앱 실행
./gradlew :bootstrap:jpackageImage   # Windows: app/bootstrap/build/jpackage/Tanager/Tanager.exe
```

Windows 에서는 `./gradlew` 대신 `gradlew.bat` 을 씁니다. 같은 작업 트리를 WSL 과 Windows 에서 번갈아 빌드해도, WSL 쪽은 `build/wsl/` 에 쓰므로 섞이지 않습니다.

## 프로젝트 구조

Gradle 모듈로 나눈 헥사고날 아키텍처입니다([ADR-0002](docs/adr/0002-hexagonal-multimodule.md)). 의존은 안쪽으로만 향하고 ArchUnit 이 검사합니다.

```
app/
├── domain/                순수 Java 규칙 (I/O · UI · 스레드 없음)
├── application/           유스케이스와 포트
├── adapter/
│   ├── pty/               pty4j / ConPTY 로 터미널 실행
│   ├── persistence/       상태 · 설정 저장 (JSON)
│   └── toolkit/claude/    Claude Code 설정 읽기 (읽기 전용)
├── platform/
│   ├── windows/           WSL · PowerShell 5.1 · cmd 처리
│   └── macos/             macOS 처리 (v1.1.0)
├── ui/                    Swing + FlatLaf + JediTerm 화면
├── bootstrap/             조립 루트와 패키징
└── architecture/          ArchUnit 규칙
build-logic/               Gradle 공통 설정(convention plugin)
docs/                      계획서(HTML) · ADR
```

기술 스택: Java 25 · Gradle 9.9 · Swing + FlatLaf · JediTerm · pty4j · Jackson · JUnit 6 · jqwik · ArchUnit · jpackage.

## 기여 · 개발 방식

- **브랜치:** `dev` 가 통합 브랜치입니다. 모든 변경은 `dev` 로 가는 PR 로 들어가고 squash 병합됩니다. `main` 은 릴리스만 받습니다(`dev → main` merge commit 후 `vX.Y.Z` 태그).
- **커밋:** 모듈 scope 가 필수인 [Conventional Commits](https://www.conventionalcommits.org/) — 예 `feat(ui): 사이드바 폴더 트리 추가`.
- **테스트 먼저:** RED → GREEN → REFACTOR. 커버리지 domain 90 % · application 80 % 이상.
- **클론 직후 한 번:**

  ```bash
  git config core.hooksPath .githooks     # 커밋 메시지 · 브랜치 이름 검사
  git config commit.template .gitmessage
  git config core.autocrlf false          # 줄바꿈은 .gitattributes 가 정함
  ```

자세한 규칙은 [`.claude/rules/git.md`](.claude/rules/git.md) 에 있습니다.

## 문서

- [프로젝트 구성 및 설계 계획서](docs/plans/01-프로젝트-구성-설계.html) — 스택 · 구조 · 방법론 · Git 전략 · CI (HTML)
- [소프트웨어 설계 기술서(SDD)](docs/plans/02-SDD.html) — 요구사항 · 설계 · 로드맵 (HTML)
- [아키텍처 결정 기록(ADR)](docs/adr/)

## 로드맵

| 버전 | 마일스톤 |
|---|---|
| **v0.1.0** | 골격 — 모듈, 아키텍처 규칙, 플랫폼 선택, 패키징 ✅ |
| v0.2.0 | 단일 터미널 — 셸 탐지, 터미널 창 하나, 종료 후 재시작, 소프트 테마 |
| v0.3.0 | 이력과 폴더 트리 — 경로 추적, 폴더 → 세션 트리, 고정 · 검색 |
| v0.4.0 | 분할 — 키보드와 마우스 |
| v0.5.0 | 복원 · 탭 · 설정 · 테마 · 리소스 표시 |
| v1.0.0 | 도구함 패널과 안정화 |
| v1.1.0 | macOS |

## 라이선스

Tanager 는 [MIT 라이선스](LICENSE)로 배포합니다. 함께 쓰는 외부 구성 요소(JediTerm, pty4j, JNA, FlatLaf, Jackson 등)는 각자의 라이선스를 따릅니다.
