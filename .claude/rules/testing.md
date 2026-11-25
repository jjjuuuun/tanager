---
paths:
  - "**/src/test/**"
  - "**/src/testFixtures/**"
---
# 테스트 규칙 (TDD)

## 사이클
RED(실패 테스트) → GREEN(최소 구현) → REFACTOR(정리). 테스트 없이 프로덕션 코드를 먼저 쓰지 않는다.
컴파일 실패도 RED 로 인정한다.

## 도구
JUnit Jupiter · AssertJ · jqwik(속성 기반) · ArchUnit. Mockito 는 쓰지 않는다 → 포트의 **수작업 Fake** 를 `src/testFixtures` 에 둔다 (예 `InMemoryHistoryRepository`, `FakeClock`, `ScriptedPtyLauncher`).

## 테스트 종류와 위치
| 종류 | 위치 | 태그 | 실행 환경 |
|---|---|---|---|
| 단위(도메인 순수 함수) | `app/domain` | 없음 | 어디서나 |
| 속성 기반(레이아웃 불변식 등) | `app/domain` | 없음 | 어디서나 |
| 유스케이스 | `app/application` (Fake 사용) | 없음 | 어디서나 |
| 어댑터 계약 | 각 adapter/platform 모듈 | `contract` | 어디서나 / OS 별 |
| OS 통합(ConPTY, powershell 5.1 · cmd) | `app/platform/windows` | `windows` | Windows |
| WSL 통합 | `app/platform/windows` | `wsl` | WSL 설치된 Windows (CI 제외) |
| 아키텍처 | `app/architecture` | 없음 | 어디서나 |

## 작성 규칙
- 이름: `메서드명_상황_기대결과` 또는 `@DisplayName("한국어 문장")`. 한 테스트 한 행위.
- 구조: given / when / then 주석으로 구분.
- jqwik `@Property` 에는 JUnit 의 `@DisplayName` 대신 jqwik 의 `@Label` 을 쓴다. `@DisplayName` 을 붙이면 jqwik 이 그 속성을 조용히 건너뛴다(SKIPPED, 실행 0회 — #41 에서 발견).
- 시간·ID·파일시스템은 포트로 주입해 결정적으로. `Thread.sleep` 금지 → `FakeClock`, Awaitility.
- 포트마다 추상 계약 테스트(`HistoryRepositoryContract`)를 두고 Fake 와 실제 구현이 같이 상속한다.
- 커버리지 게이트: domain 90%, application 80% (JaCoCo line). UI 는 게이트 없음.
