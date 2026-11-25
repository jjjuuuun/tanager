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
- **브랜치 정리**: 병합된 작업 브랜치는 GitHub 이 자동 삭제하고 Claude 가 로컬에서도 지운다. 스파이크 브랜치는 결과 PR 이 병합되면 `archive/<브랜치명>` 태그(주석 태그, 승인 필요)로 커밋을 보관한 뒤 원격 · 로컬에서 지운다. **main · dev 는 절대 지우지 않는다**(ruleset 이 삭제 금지). 남는 브랜치는 main · dev 둘뿐이어야 한다.
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

**확인 지점**: 사용자가 하는 일은 **마일스톤마다 한 번, 릴리스 PR(dev → main) 병합**뿐이다. 그동안의 PR · 리뷰 코멘트를 모아 보고 병합을 결정한다(사용자 결정 2026-11-24, 2026-11-25 개정 · ADR-0005). 그 밖의 병합(작업 PR squash · 역병합 · hotfix)과 태그 · Release 는 Claude 가 묻지 않고 한다. 중간에 보고 싶으면 PR · 리뷰 코멘트는 언제든 남아 있고, 문제가 있으면 릴리스 전에 되돌리는 PR 을 만든다.

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
| 13. main 에서 `git tag -a vX.Y.Z` → `git push origin vX.Y.Z` → release 워크플로 → 마일스톤 닫기 | Claude | `/release` |

긴급 수정: `hotfix/<n>-<slug>`(main 에서 분기) → 1~9 와 같되 base = main, 조건이 맞으면 Claude 가 **merge commit** 으로 병합(`gh pr merge <n> --merge`) → 13(패치 버전) → main → dev 역병합 PR 도 Claude 가 만들고 merge commit 으로 병합.

- Claude 가 절대 하지 않는 것: **릴리스 PR(dev → main) 병합**, `--rebase`·`--admin`·`--auto` 병합, 작업 PR 의 merge commit 병합, `gh pr review --approve`, main·dev 직접 커밋/푸시/병합, 강제 푸시(`--force-with-lease` 는 작업 브랜치에서만), `gh release delete`. `settings.json` deny + `guard-git.sh` 가 막는다. 병합은 PR 의 head · base 를 조회해 작업 PR(→ dev) = `--squash`, 역병합(main → dev) · `hotfix/*` → main = `--merge` 일 때만 통과한다.
- 태그 생성 · 푸시와 `gh release create` 는 승인 없이 한다. 승인 창이 뜨는 것은 원격 태그 · ref 삭제(`git push origin :<ref>`)와 `--tags` · `--follow-tags` 푸시뿐이다.
- `guard-git.sh` 는 명령 **실행 전** 브랜치로 판단한다. 브랜치 생성과 커밋을 한 명령에 묶지 말고 따로 실행한다. 전역 옵션(`-C`·`-c`)을 붙여도 같은 검사를 받고, 훅 경로 변경(`.githooks` 외), `--mirror`·`--delete` 푸시, `gh api` 로 PR 병합·닫기도 막는다.
- 커밋 메시지는 파일로 넘긴다(`git commit -F <파일>`). 메시지 본문의 `dev`·`push` 같은 글자를 guard 가 명령으로 오인하지 않게.
- PR 제목은 **66자 이하**로 쓴다. squash 커밋 헤더에 ` (#N)` 이 붙어도 72자를 넘지 않게.
- 리뷰 코멘트는 `gh` 에 로그인한 사용자 계정 이름으로 달린다. 그래서 본문 첫 줄에 `## Claude 리뷰` 를 붙인다.
- PR 본문 수정은 `gh api -X PATCH repos/{owner}/{repo}/pulls/<n> -F body=@<파일>` 로 한다. `gh pr edit` 은 GitHub classic Projects 지원 종료 오류(`repository.pullRequest.projectCards`)로 실패한다.

## 워크플로(`.github/workflows`)의 PowerShell 단계
v0.1.0 릴리스 실패(#32)에서 나온 규칙. Windows 러너의 `shell: pwsh` 단계에 적용한다.
- `-` 로 시작하는 인수에 변수가 들어가면 **따옴표로 감싼다**: `"-Pversion=$env:X"`. 따옴표가 없으면 PowerShell 이 `$env:X` 를 펼치지 않고 글자 그대로 넘긴다.
- PowerShell 은 외부 명령(`./gradlew` 등)이 실패해도 다음 줄로 간다. 여러 줄 단계에서는 외부 명령마다 `if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }` 를 붙인다. 마지막 줄 하나뿐이면 필요 없다.
- release · CI 의 pwsh 단계를 바꾸면 로컬에서도 **PowerShell 로** 그대로 돌려 본다(`powershell.exe -NoProfile -File <단계.ps1>`). bash 로 같은 명령을 돌린 확인은 확인이 아니다.
  - WSL 에서 돌리면 Windows Gradle 데몬이 출력 파이프를 잡고 있어 명령이 끝나지 않을 수 있다. 결과는 산출물(zip 등)과 종료 코드로 본다.
- 저장소 최초 설정(빈 시작 커밋 · dev 생성 · 기본 브랜치 main · ruleset · 라벨 · 마일스톤)은 사용자가 `.github/scripts/bootstrap-repo.sh <owner/repo>` 로 한 번 실행한다. M1 이후 `--with-checks` 로 다시 실행해 필수 CI 검사를 켠다.

## 마일스톤
- 원본: `.github/milestones.json` (SDD §13.1 과 같은 내용). 마일스톤 범위·요구사항이 바뀌면 SDD 와 이 파일을 **같은 PR 에서** 바꾼다(`docs(docs)` 커밋).
- dev 에 병합되면 `.github/workflows/milestones.yml` 이 GitHub 마일스톤을 만들거나 갱신한다(닫기·삭제는 하지 않음). 수동: `.github/scripts/sync-milestones.sh [--dry-run]`.
- 이슈·PR 은 SDD 요구사항 ID → `requirements` 로 마일스톤을 정한다. 스파이크는 M0, 빌드·CI 골격은 M1.
- 마일스톤 하나 = 릴리스 하나(`release` 필드) = dev → main 릴리스 PR 하나. M0 은 릴리스 없음. M1(스캐폴딩)은 사용자 결정으로 v0.1.0(2026-11-25) — 이후 M2 v0.2.0 … M5 v0.5.0, M6 v1.0.0.
