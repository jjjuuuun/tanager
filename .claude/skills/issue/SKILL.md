---
name: issue
description: 기능·버그 설명을 받아 .github/ISSUE_TEMPLATE 형식(인수 조건 Given/When/Then, 주 영향 모듈, SDD 요구사항 ID)으로 GitHub 이슈를 만들고 번호를 돌려준다. /tdd 를 이슈 번호 없이 시작했을 때도 쓴다.
argument-hint: "<기능 또는 버그 설명>"
---
# /issue $ARGUMENTS

1. **종류 결정** — 새 동작·개선이면 기능(`feature.yml`), 기대와 다른 동작이면 버그(`bug.yml`). 템플릿 파일을 읽어 필드를 그대로 따른다.
2. **중복 확인** — `gh issue list --state open --search "<핵심어>"`. 같은 이슈가 있으면 만들지 않고 그 번호를 쓴다.
3. **SDD 연결** — `docs/plans/02-SDD.html` 에서 해당 요구사항 ID(FR-*/NFR-*)를 찾는다. 없으면 "없음(신규)" 으로 적고 보고에 알린다.
4. **본문 작성** (`$CLAUDE_PROJECT_DIR` 밖의 임시 파일에 Markdown 으로)
   - 기능: `### 문제 / 동기` · `### 제안` · `### 인수 조건 (Given / When / Then)` · `### 주 영향 모듈` · `### SDD 요구사항 ID`
   - 버그: `### 현상` · `### 재현 절차` · `### 기대 동작` · `### 환경` · `### 로그`
   - 인수 조건은 테스트로 옮길 수 있게 행위 하나당 Given/When/Then 한 묶음. 모르는 값(환경, 로그)은 지어내지 않고 `확인 필요` 로 둔다.
   - 본문 마지막 줄: `🤖 Generated with [Claude Code](https://claude.com/claude-code)`
5. **제목** — `<type>(<scope>): <subject>` (커밋 헤더 형식, 72자 이하). 이 제목이 나중에 PR 제목 후보가 된다.
6. **마일스톤** — `.github/milestones.json` 에서 SDD 요구사항 ID 가 `requirements` 에 들어 있는 마일스톤의 `title` 을 고른다. 스파이크는 M0, 빌드·CI 골격은 M1. 맞는 것이 없으면 마일스톤 없이 만들고 보고에 알린다.
7. **라벨** — `gh label list` 에 `type:feat`/`type:fix` 가 없으면 `gh label create` 로 먼저 만든다.
8. `gh issue create --title "..." --body-file <파일> --label type:feat --milestone "<마일스톤 제목>"` → 이슈 번호·URL·마일스톤을 알리고, 브랜치 이름 후보 `<type>/<번호>-<slug>` 를 함께 보여 준다.
