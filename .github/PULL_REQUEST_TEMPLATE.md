<!--
PR 제목 = squash 커밋 헤더. 반드시 Conventional Commits 형식:
  <type>(<scope>)[!]: <subject>     예) feat(ui): 사이드바 경로 트리 추가
CI(pr-lint)가 제목과 브랜치 이름을 검사합니다.
병합 방법: base 가 dev 면 Squash and merge · main 이면 Create a merge commit (Claude 가 본문 첫 줄에 적는다)
릴리스 PR(dev → main)은 /release 가 따로 만든다.
-->

## 1. 요약
<!-- 이 PR 이 무엇을 왜 바꾸는지 1~3문장 -->

## 2. 관련 이슈
Closes: #

## 3. 변경 유형
- [ ] feat — 기능 추가
- [ ] fix — 버그 수정
- [ ] perf — 성능 개선
- [ ] refactor — 동작 변경 없는 구조 개선
- [ ] test / docs / build / ci / chore
- [ ] **BREAKING CHANGE** (설정·저장 파일 스키마·키 바인딩 호환성 깨짐)

## 4. 변경 사항
<!-- 모듈별로. 예) tanager-domain: LayoutTree.resize 추가 -->
-

## 5. 설계 영향
- [ ] 포트(인터페이스) 추가·변경 없음 / 있음 → 대상: 
- [ ] 모듈 의존 방향 변경 없음 (ArchUnit 통과)
- [ ] 저장 스키마(`state.json`) 변경 없음 / 있음 → `schemaVersion` 올림 + 마이그레이션 테스트
- [ ] ADR 필요 없음 / 작성함 → `docs/adr/NNNN-*.md`

## 6. 테스트
### 자동
- [ ] 실패 테스트를 먼저 작성했다 (TDD: RED → GREEN → REFACTOR)
- [ ] `./gradlew check` 로컬 통과

### 수동 확인 환경
- [ ] Windows 11 + Windows PowerShell 5.1
- [ ] Windows 11 + cmd
- [ ] WSL2 (배포판: ______ )
- [ ] macOS (해당 시)

### 확인 절차
1.
2.

## 7. 스크린샷 / 녹화
<!-- UI 변경 시 전·후 -->

## 8. 셀프 체크리스트
- [ ] PR 제목이 Conventional Commits 형식이다
- [ ] 브랜치 이름이 `<type>/<이슈번호>-<slug>` 이다
- [ ] base 브랜치(`dev`, hotfix 는 `main`) 최신으로 rebase 했다
- [ ] 도메인 계층에 외부 라이브러리·I/O 가 들어가지 않았다
- [ ] 새 public API 에 null 을 반환하지 않는다 (Optional / Result 사용)
- [ ] `/code-review` 를 돌리고 지적 사항을 처리했다
- [ ] 문서(CLAUDE.md, rules, 계획서)를 바꿔야 하면 함께 바꿨다

## 9. 리뷰어 참고
<!-- 집중해서 봐 줬으면 하는 부분, 알려진 한계 -->
