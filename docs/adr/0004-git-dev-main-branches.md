# 0004. Git 브랜치: dev(통합) + main(릴리스 전용)

- 상태: 채택
- 날짜: 2026-11-24
- 대체: 계획서 1 초안의 "GitHub Flow(main 하나) + 태그 릴리스"

## 맥락
개발은 Claude 가 작업 브랜치·PR 까지 진행하고, 사용자가 리뷰 코멘트를 보고 병합을 결정한다. 사용자는 "개발은 전부 dev 에서 하고, 릴리스할 때만 main 으로 병합"하기를 원한다. main 은 언제나 마지막 릴리스와 같아야 하고(설치 파일과 소스가 일치), 개발 중인 기능이 main 에 섞이면 안 된다.

## 선택지
1. GitHub Flow — main 하나, 태그로 릴리스. 단순하지만 main 에 미완성 마일스톤이 쌓인다.
2. **Git Flow 간소화** — `dev`(통합) + `main`(릴리스 전용 · 기본 브랜치) + 작업 브랜치 + `hotfix/*`. `release/*` 브랜치는 두지 않는다(버전은 태그에서 주입하므로 릴리스 준비 커밋이 없다).
3. 전체 Git Flow — `release/*` 까지. 혼자 + 에이전트 규모에서는 관리 비용만 늘어난다.

## 결정
2번.
- 작업 브랜치 → dev: **squash merge** (PR 제목 = dev 의 커밋 헤더).
- dev → main 릴리스 PR · hotfix → main · main → dev 역병합: **merge commit** (dev 와 main 의 이력을 이어 두어, 다음 릴리스 PR 에 같은 변경이 다시 나타나거나 충돌하지 않게).
- 태그는 main 의 커밋에만(release 워크플로가 검사). 마일스톤 하나 = 릴리스 PR 하나.
- GitHub 기본 브랜치는 **main** (사용자 결정, 2026-11-24): 저장소 첫 화면과 클론 직후 브랜치가 마지막 릴리스다. 대가로 ① 작업 PR 은 `--base dev` 를 명시해야 하고(pr-lint 가 검사) ② `Closes:` 자동 닫기가 dev 병합 때 동작하지 않으므로 `close-issues.yml` 이 대신 닫는다.

## 결과
- 강제: `.githooks/pre-commit`·`pre-push`(main·dev 직접 커밋·푸시 차단), `.claude/hooks/guard-git.sh`, `pr-lint.yml`(head·base·제목 조합), `release.yml`(태그가 main 위인지), GitHub ruleset(`bootstrap-repo.sh`: main = merge 만, dev = squash·merge).
- GitHub 은 PR 마다 병합 방법을 강제하지 못하므로 dev 에서는 squash 와 merge 를 모두 허용하고, Claude 가 PR 본문 첫 줄에 병합 방법을 적는다.
- 비공개 저장소 + 무료 플랜이면 ruleset 이 동작하지 않는다. 그때는 로컬 훅과 Claude 훅만으로 보호한다.
- 바뀐 문서: 계획서 1 §6·§8·§9·§12, `.claude/rules/git.md`, `/pr` · `/release` · `/tdd` 스킬.
