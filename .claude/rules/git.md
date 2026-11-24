# Git 규칙

전체 근거·도식: `docs/plans/01-프로젝트-구성-설계.html` §6~9.

## 브랜치 (Git Flow 간소화: main · dev + 작업 브랜치)
```
main  ●────────────────●(v0.1.0)──────────●(v0.1.1)───────●(v0.2.0)     릴리스만. merge commit + 태그
       \              / merge            / merge   \      / merge
dev     ●──●──●──●──●──────●──────────────────────●──●──●               통합. 작업 브랜치의 squash 커밋
           \squash/          \                    / 역병합(merge)
feat/12-…   ●─●─●             hotfix/31-… ●──────●  (main 에서 분기 → main 으로)
```
- `main`: **릴리스 전용 · GitHub 기본 브랜치**(클론 직후 브랜치, 저장소 첫 화면 = 마지막 릴리스). 들어오는 것은 ① dev → main 릴리스 PR ② `hotfix/*` PR 뿐, 둘 다 **merge commit**. 태그는 main 에만.
- `dev`: **통합 브랜치**. 모든 작업 브랜치는 dev 에서 갈라져 dev 로 **squash merge**. GitHub 의 기본 base 가 main 이므로 `gh pr create` 에 `--base dev` 를 반드시 쓴다(틀리면 pr-lint 가 막음).
- 이슈 닫기: GitHub 은 기본 브랜치(main) 병합 때만 `Closes:` 로 이슈를 닫는다. 그래서 `.github/workflows/close-issues.yml` 이 **dev push** 때 커밋 메시지(= squash/merge 시 PR 제목+본문)의 `Closes: #N` 이슈를 닫는다(마일스톤 진행률·`/release` 준비 확인이 맞게 됨). PR 본문에 `Closes: #N` 을 꼭 쓴다.
- main · dev 모두 직접 커밋·푸시·병합 금지 (`.githooks/pre-commit`·`pre-push` + `.claude/hooks/guard-git.sh` + GitHub ruleset).
- 작업 브랜치: `<type>/<이슈번호>-<kebab-slug>` — 예 `feat/12-split-pane-resize`. dev 에서 분기, base = dev.
  - type: `feat fix perf refactor test docs build ci chore spike`
  - `spike/` 는 실험용. PR 병합 금지(CI 가 막음). 결론은 `docs/adr/` 에 남기고 `feat/` 로 다시 구현.
- 긴급 수정: `hotfix/<이슈번호>-<slug>` — **main 에서 분기, base = main**. 병합 후 패치 태그 → main → dev **역병합 PR**(merge commit).
- 팀 모드 하위 브랜치: `<부모 브랜치>--<역할>` (예 `feat/12-split-pane--domain`). 로컬에서 부모 브랜치로 병합하며 PR 을 만들지 않는다.
- 태그: `vX.Y.Z` (SemVer). main 의 커밋에만(release 워크플로가 검사). `v1.0.0` 전에는 MINOR 가 호환성 깨짐을 포함할 수 있다.

| PR | head → base | 제목 | 병합 방법 |
|---|---|---|---|
| 기능·수정 | `<type>/<n>-<slug>` → `dev` | `<type>(<scope>): ...` | **Squash and merge** |
| 릴리스 | `dev` → `main` | `chore(release): vX.Y.Z` | **Create a merge commit** |
| 긴급 수정 | `hotfix/<n>-<slug>` → `main` | `fix(<scope>): ...` | **Create a merge commit** |
| 역병합 | `main` → `dev` | `chore(release): vX.Y.Z 역병합` | **Create a merge commit** |

`pr-lint.yml` 이 head·base·제목 조합을 검사한다. Claude 는 PR 본문 첫 줄에 병합 방법을 적는다.

**확인 지점**: 작업 PR(→ dev)은 조건이 맞으면 Claude 가 squash 병합한다. 사용자는 **마일스톤마다 한 번, 릴리스 PR(dev → main)** 에서 그동안의 PR · 리뷰 코멘트를 모아 보고 main 병합을 결정한다(사용자 결정, 2026-11-24 · ADR-0005). 중간에 보고 싶으면 PR · 리뷰 코멘트는 언제든 남아 있고, 문제가 있으면 릴리스 전에 되돌리는 PR 을 만든다.

## 커밋 메시지 (`.gitmessage`, `.githooks/commit-msg` 가 검사)
```
<type>(<scope>)[!]: <subject>      # 72자 이하, 한국어 명사형 종결, 마침표 없음

<body>                             # 무엇을·왜. 72자 줄바꿈

<footer>                           # Closes: #12 · Refs: #12 · BREAKING CHANGE: ...
```
- type: `feat fix perf refactor test docs build ci chore revert`
- scope: `domain app pty persist platform-win platform-mac ui toolkit bootstrap build ci docs claude deps release`(release 는 릴리스·역병합 PR 전용)
- Claude 가 만든 커밋은 footer 마지막 줄에 `Co-Authored-By:` 를 붙인다.

## 커밋 시점 (이 순서를 지킨다)
1. RED — 실패 테스트 작성. **커밋하지 않는다.**
2. GREEN — 테스트 통과 직후 커밋 (`feat`/`fix`, 테스트와 구현을 한 커밋에).
3. REFACTOR — 동작 변경 없이 정리, 전체 테스트 GREEN 확인 후 별도 커밋 (`refactor`).
4. 빌드·CI·문서·설정 변경은 각자 별도 커밋 (`build` / `ci` / `docs` / `chore`).
- 금지: RED 상태 커밋, 관심사 혼합(포맷+로직, 리팩터링+기능), 생성물 커밋.

## 역할과 PR 흐름 (이 순서를 지킨다)
| 단계 | 담당 | 도구 |
|---|---|---|
| 1. 이슈 생성 → 번호·마일스톤 확보 | Claude | `/issue` |
| 2. `git switch dev && git pull --ff-only` → `git switch -c <type>/<번호>-<slug>` | Claude | `/tdd` 0단계 |
| 3. TDD 사이클 커밋 반복 | Claude | `/tdd` (서브에이전트) |
| 4. `git fetch && git rebase origin/dev` | Claude | `/pr` |
| 5. `./gradlew check` 로컬 통과 | Claude | `/pr` |
| 6. push → **Draft PR → dev** (템플릿 전 항목, 이슈와 같은 마일스톤, 첫 줄에 병합 방법) | Claude | `/pr` |
| 7. CI 전부 green (실패 시 고쳐서 다시 푸시) | Claude | `/pr` |
| 8. 리뷰 → 차단 지적 수정 → **리뷰를 PR 에 코멘트로 게시**(권고: 병합 권장/보류 권장) | Claude | reviewer · `/code-review` · `gh pr review --comment` |
| 9. Ready for review | Claude | `gh pr ready` |
| 10. 조건이 맞으면 **Squash merge** — CI green · 차단 0건 · 권고 "병합 권장". 아니면 멈추고 사용자에게 넘김 | Claude | `gh pr merge <n> --squash --delete-branch` |
| 11. 마일스톤의 이슈가 모두 닫히면 **릴리스 PR dev → main** 생성 — 포함된 PR · 각 리뷰 권고 · 남은 지적 · 수동 확인 항목을 모아 정리 | Claude | `/release` |
| 12. **사용자 확인 지점(마일스톤마다 한 번)** — 릴리스 PR 을 보고 merge commit 병합 또는 보류 | **사용자** | GitHub 화면 |
| 13. main 에서 `git tag -a vX.Y.Z` → `git push origin vX.Y.Z` → release 워크플로 → 마일스톤 닫기 | Claude, **사용자 승인 후** | `/release` (승인 창) |

긴급 수정: `hotfix/<n>-<slug>`(main 에서 분기) → 1~9 와 같되 base = main, **병합은 사용자**(merge commit) → 13(패치 버전) → main → dev 역병합 PR(Claude 생성, 사용자 병합).

- Claude 가 절대 하지 않는 것: **main 으로 가는 PR 병합**(릴리스 · hotfix · 역병합), 작업 PR 의 squash 외 병합(`--merge`·`--rebase`·`--admin`·`--auto`), `gh pr close`, `gh pr review --approve`, main·dev 직접 커밋/푸시/병합, 강제 푸시(`--force-with-lease` 는 작업 브랜치에서만). `settings.json` deny + `guard-git.sh` 가 막는다(병합은 PR 의 base 를 조회해 dev 일 때만 통과).
- 태그 생성·태그 푸시·`gh release create` 는 allow 규칙과 상관없이 승인 창이 뜬다(`guard-git.sh` 가 ask 반환).
- `guard-git.sh` 는 명령 **실행 전** 브랜치로 판단한다. 브랜치 생성과 커밋을 한 명령에 묶지 말고 따로 실행한다. 전역 옵션(`-C`·`-c`)을 붙여도 같은 검사를 받고, 훅 경로 변경(`.githooks` 외), `--mirror`·`--delete` 푸시, `gh api` 로 PR 병합·닫기도 막는다.
- 커밋 메시지는 파일로 넘긴다(`git commit -F <파일>`). 메시지 본문의 `dev`·`push` 같은 글자를 guard 가 명령으로 오인하지 않게.
- PR 제목은 **66자 이하**로 쓴다. squash 커밋 헤더에 ` (#N)` 이 붙어도 72자를 넘지 않게.
- 리뷰 코멘트는 `gh` 에 로그인한 사용자 계정 이름으로 달린다. 그래서 본문 첫 줄에 `## Claude 리뷰` 를 붙인다.
- 저장소 최초 설정(빈 시작 커밋 · dev 생성 · 기본 브랜치 main · ruleset · 라벨 · 마일스톤)은 사용자가 `.github/scripts/bootstrap-repo.sh <owner/repo>` 로 한 번 실행한다. M1 이후 `--with-checks` 로 다시 실행해 필수 CI 검사를 켠다.

## 마일스톤
- 원본: `.github/milestones.json` (SDD §13.1 과 같은 내용). 마일스톤 범위·요구사항이 바뀌면 SDD 와 이 파일을 **같은 PR 에서** 바꾼다(`docs(docs)` 커밋).
- dev 에 병합되면 `.github/workflows/milestones.yml` 이 GitHub 마일스톤을 만들거나 갱신한다(닫기·삭제는 하지 않음). 수동: `.github/scripts/sync-milestones.sh [--dry-run]`.
- 이슈·PR 은 SDD 요구사항 ID → `requirements` 로 마일스톤을 정한다. 스파이크는 M0, 빌드·CI 골격은 M1.
- 마일스톤 하나 = 릴리스 하나(`release` 필드) = dev → main 릴리스 PR 하나. M0 은 릴리스 없음. M1(스캐폴딩)은 사용자 결정으로 v0.1.0(2026-11-25) — 이후 M2 v0.2.0 … M5 v0.5.0, M6 v1.0.0.
