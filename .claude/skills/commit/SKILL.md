---
name: commit
description: 스테이징된 변경을 Tanager 커밋 규칙(.gitmessage)에 맞는 메시지로 커밋한다. 관심사가 섞여 있으면 나눠서 커밋한다.
allowed-tools: Bash(git status *), Bash(git diff *), Bash(git add *), Bash(git commit *), Bash(git log *)
---
# /commit

1. `git status`, `git diff --staged`, `git diff` 로 변경을 본다. 스테이징된 것이 없으면 변경 전체를 대상으로 한다.
2. 관심사별로 묶는다: 기능+테스트 / 리팩터링 / 빌드 / CI / 문서 / 설정. 섞여 있으면 묶음마다 `git add <파일>` 후 따로 커밋한다.
3. 테스트가 실패 중(RED)이면 커밋하지 않고 알린다 — `./gradlew :<모듈>:test` 로 확인.
4. 메시지 형식 (`.gitmessage`):
   ```
   <type>(<scope>)[!]: <subject>

   <body: 무엇을·왜, 72자 줄바꿈>

   Refs: #<이슈번호>       ← 브랜치 이름의 번호
   Co-Authored-By: ...
   ```
   - scope 는 바뀐 모듈: domain app pty persist platform-win platform-mac ui toolkit bootstrap build ci docs claude deps
   - subject 는 한국어 명사형 종결, 72자 이하, 마침표 없음
5. `git commit -F -` (heredoc) 으로 커밋. `--no-verify` 금지. 훅이 거부하면 메시지를 고쳐 다시 시도한다.
6. `git log --oneline -5` 로 결과를 보여 준다.
