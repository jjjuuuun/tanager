---
name: pr
description: 작업 브랜치를 dev(hotfix 는 main) 기준으로 rebase·검사·푸시해 Draft PR 을 열고, CI 통과 후 리뷰를 PR 에 코멘트로 남기고 Ready for review 로 바꾼다. 병합·닫기는 사용자가 결정하므로 하지 않는다.
---
# /pr

순서를 지킨다. 실패하면 그 단계에서 멈추고 원인을 보고한다.
역할: 여기까지 전부 Claude. **Squash merge 할지 PR 을 닫을지는 사용자가 PR 의 리뷰 코멘트를 보고 정한다.**

## A. PR 열기
1. 브랜치 이름이 `<type>/<번호>-<slug>` 인지 확인. `main` · `dev` 이면 중단. **base** 를 정한다: `hotfix/*` → `main`, 그 외 → `dev`. (dev → main 릴리스는 `/release`)
2. 작업 트리가 깨끗한지 확인 (남은 변경은 `/commit` 먼저).
3. `git fetch origin && git rebase origin/<base>` — 충돌 시 중단하고 사용자에게 알린다.
4. `./gradlew check` 통과 확인.
5. PR 제목 = `<type>(<scope>)[!]: <subject>` (72자 이하). 브랜치의 커밋들을 대표하는 한 줄. squash 후 dev 의 커밋 헤더가 된다.
6. 본문 **첫 줄에 병합 방법**을 쓴다 — base dev: `> 병합 방법: **Squash and merge**` · base main(hotfix): `> 병합 방법: **Create a merge commit**`. 그 아래 `.github/PULL_REQUEST_TEMPLATE.md` 의 1~9절을 모두 채운다. `Closes: #<번호>`. 직접 확인하지 않은 수동 확인 항목은 체크하지 않는다. 마지막 줄 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
7. `git push -u origin HEAD` (rebase 로 이미 푸시한 브랜치가 바뀌었으면 `git push --force-with-lease`) → `gh pr create --draft --base <base> --title ... --body-file ... --milestone "<이슈의 마일스톤>"` (마일스톤은 `gh issue view <번호> --json milestone -q .milestone.title`)

## B. CI
8. `gh pr checks <번호> --watch` 로 기다린다. 실패하면 `gh run view --log-failed` 로 원인을 찾아 고치고(관심사별 커밋) 다시 푸시한다. 같은 실패가 두 번 반복되면 멈추고 보고한다.

## C. 리뷰 (PR 에 남긴다)
9. `reviewer` 서브에이전트로 `origin/<base>...HEAD` 를 검토한다. 필요하면 `/code-review` 도 돌린다.
10. **차단** 지적은 고친다 → 커밋 → 푸시 → CI → 9 로. 수정 권장·사소 항목은 고치거나 남기는 이유를 적는다.
11. 최종 리뷰를 `gh pr review <번호> --comment --body-file <파일>` 로 남긴다 (`--approve` 는 쓰지 않는다. 자기 PR 은 승인할 수 없고, 결정은 사용자 몫).
    ```
    ## Claude 리뷰 — <PR 제목>
    **권고: 병합 권장 | 보류 권장** — 한 줄 근거

    ### 확인한 것
    - CI: lint ✅ test-core ✅ test-windows ✅ pr-lint ✅
    - 인수 조건 → 테스트 대응: (이슈의 Given/When/Then 마다 테스트 이름)
    - 아키텍처·순수성·TDD·플랫폼·스키마·커밋·동시성 점검 결과

    ### 리뷰 중 고친 것
    - <커밋 해시> <내용>

    ### 남은 지적 (심각도별)
    - [수정 권장] `파일:줄` 문제 — 남긴 이유

    ### 사용자가 직접 확인할 것
    - 수동 확인 환경(Windows+pwsh / WSL / macOS) 중 Claude 가 못 본 것
    - UI 변경이면 확인 절차
    ```
12. `gh pr ready <번호>` 로 Draft 를 해제한다.
13. PR URL 과 권고 한 줄을 보고하고 멈춘다. **`gh pr merge`·`gh pr close` 는 하지 않는다** (`guard-git.sh` 가 차단).

## D. 사용자가 결정한 뒤
- 병합됨(→ dev): `git switch dev && git pull --ff-only`, 로컬 브랜치 `git branch -D <브랜치>`(squash 라 `-d` 는 거부됨). 그 마일스톤의 열린 이슈가 0개가 되면 `/release` 를 제안한다.
- 병합됨(hotfix → main): 패치 태그를 `/release` 의 E 단계로(승인 필요) → 이어서 **역병합 PR** `gh pr create --base dev --head main --title "chore(release): vX.Y.Z 역병합"` (본문 첫 줄 `> 병합 방법: **Create a merge commit**`).
- 수정 요청: PR 코멘트를 읽고 같은 브랜치에서 C 를 다시 한다.
- 닫힘: 이유를 확인하고, 필요하면 이슈에 결론을 코멘트로 남긴다.
