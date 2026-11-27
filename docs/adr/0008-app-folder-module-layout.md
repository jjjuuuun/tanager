# 0008. 모듈 폴더 구조: app/ 아래 계층별 폴더, 이름에 접두사 · 하이픈 없음

- 상태: 채택
- 날짜: 2026-11-25
- 개정 대상: ADR-0002(모듈 경계와 의존 방향은 그대로, 폴더 · 이름만 바뀐다)

## 맥락
M1 에서는 저장소 루트에 `tanager-domain` · `tanager-adapter-pty` 같은 모듈 폴더 10개를 나란히 두었다.
- 사용자 의견: 루트가 어수선해서 구조가 한눈에 들어오지 않는다.
- 사용자 결정(2026-11-25)
  - 코드 모듈을 `app/` 하나 아래로 모은다.
  - `tanager-` 접두사를 붙이지 않는다.
  - 폴더 이름에 `-` 를 쓰지 않는다.
  - 어댑터는 `app/adapter/` 아래로 모은다.

M2 #43 을 시작하기 직전이었다. 모듈이 더 늘기 전에 바꾸는 것이 가장 싸다.

## 선택지
1. 루트에 그대로 두고 접두사만 뗀다(`domain/`, `adapter-pty/`). 하이픈이 남고 루트도 여전히 어수선하다.
2. **`app/` 아래 계층별 중첩 폴더**(`app/adapter/pty`). Gradle 경로가 폴더 경로와 같다.
3. Java 패키지처럼 `app/src/...` 단일 모듈로 합친다. 모듈 경계(물리적 의존 강제)를 잃는다.

## 결정
2번.
```
app/domain               :domain
app/application          :application
app/adapter/pty          :adapter:pty
app/adapter/persistence  :adapter:persistence
app/adapter/toolkit/claude :adapter:toolkit:claude   (다른 AI 도구는 toolkit/<도구>)
app/platform/windows     :platform:windows
app/platform/macos       :platform:macos
app/ui                   :ui
app/bootstrap            :bootstrap     (조립 루트 + 패키징, 옛 tanager-app)
app/architecture         :architecture  (ArchUnit)
```
- **`platform/` 을 `adapter/` 와 따로 둔다.** 플랫폼 모듈도 포트를 구현하는 어댑터다. 다만 OS 마다 하나씩 ServiceLoader 로 골라지고, 새 OS 는 이 폴더에 모듈 하나를 더하면 된다는 확장 규칙이 다르다.
- **`ui` 도 따로 둔다.** 사용자가 직접 보는 주도(driving) 어댑터이고 규모가 가장 크다. 의존 규칙은 어댑터와 같다(ArchUnit 의 Adapter · Platform · Ui 층은 서로 의존 금지).
- **조립 루트는 `bootstrap` 이다.** `app/app` 을 피했고 커밋 scope `bootstrap` 과 맞춘다. Java 패키지 `tanager.app` → `tanager.bootstrap`. 나머지 패키지는 그대로다.
- **jar 이름은 `tanager-<경로>.jar` 다**(예 `tanager-adapter-pty.jar`). 프로젝트 이름(`pty` · `windows`)만으로는 배포본 `app/` 폴더에서 뜻이 흐려진다.
- 묶음 폴더(`:adapter` · `:adapter:toolkit` · `:platform`)는 빌드 파일이 없는 빈 Gradle 프로젝트다.
- 커밋 scope(`pty` · `platform-win` · `ui` …)는 바꾸지 않는다. 폴더 이름이 아니라 관심사 이름이기 때문이다.

## 결과
- 루트에는 `app/` · `build-logic/` · `docs/` · 빌드 파일만 남는다.
- 다음을 함께 바꿨다.
  - 태스크 경로: `:tanager-domain:test` → `:domain:test`
  - 산출물 경로: `tanager-app/build/jpackage` → `app/bootstrap/build/jpackage`
  - CI · release 워크플로, 규칙 문서, README, 계획서
- ADR 0001~0007 본문은 당시 기록이라 옛 이름을 그대로 둔다.
- `.gitignore` 의 IntelliJ `out/` 규칙은 `app/` 아래 각 깊이로 바꿨다. `port/out` 패키지는 무시되지 않는다.
