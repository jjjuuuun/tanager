# 0002. 헥사고날 아키텍처 + Gradle 멀티 모듈

- 상태: 채택
- 날짜: 2026-11-24
- 관련: SDD §4, `.claude/rules/architecture.md`

## 맥락
Windows 를 먼저 끝내고 macOS 를 추가한다. OS·셸·UI 라이브러리에 따라 바뀌는 부분과 바뀌지 않는 규칙(경로 이력, 레이아웃 연산, 복원)을 분리해야 한다. 에이전트가 병렬로 작업할 때 파일 충돌을 줄이려면 모듈 경계가 물리적으로 있어야 한다.

## 선택지
1. 단일 모듈 + 패키지 규칙 — 간단하지만 의존 위반을 컴파일러가 못 막음.
2. **멀티 모듈(domain / application / adapter-* / platform-* / ui / app)** — Gradle 의존 선언 자체가 경계. ArchUnit 으로 패키지 수준 보강.
3. JPMS(module-info) 강제 — JediTerm·pty4j 가 자동 모듈이라 이득 대비 마찰이 큼.

## 결정
2번. JPMS 는 쓰지 않는다. 플랫폼 구현은 `java.util.ServiceLoader` 로 런타임에 하나를 고른다.

## 결과
- macOS 지원 = `tanager-platform-macos` 추가 + CI 매트릭스 한 줄. 다른 모듈 무변경이 목표이자 검증 기준.
- 모듈 수가 많아 빌드 설정 중복 → `build-logic` 컨벤션 플러그인으로 묶는다.
