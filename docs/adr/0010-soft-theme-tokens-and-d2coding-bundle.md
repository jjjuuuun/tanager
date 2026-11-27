# 0010. 소프트 테마 구현 방식(FlatLaf 하위 클래스 + 토큰 파일)과 D2Coding 번들

- 상태: 채택(#45, 2026-11-25) — 터미널 글꼴은 이름으로 찾지 않고 번들 Font 객체를 직접 쓰도록 ADR-0011 로 개정
- 날짜: 2026-11-25
- 관련: #45, SDD §8.5 · §11, FR-UI-02 · 03 · 06, ADR-0001(글꼴), ADR-0006(색 값)

## 맥락
ADR-0006 과 SDD §8.5 의 색 토큰 12개를 실제 FlatLaf 테마로 만든다. 나중에 바꾸기 어려운 것이 두 가지 있다.

1. **테마 파일의 형식과 위치.** 모든 UI 코드가 이 파일의 키로 색을 읽는다. 테마 전환(M5)과 대비 검사도 이 형식에 묶인다.
2. **글꼴 바이너리 커밋.** git 이력에 영구히 남는다(약 8.5MB). 수정 · 부분 집합 추출을 하면 OFL 의 예약 글꼴 이름(RFN) 조항 때문에 이름을 바꿔야 한다.

## 선택지 (FlatLaf 3.8)
| 방식 | 장점 | 단점 |
|---|---|---|
| (a) `SoftDarkLaf extends FlatDarkLaf` + 같은 패키지의 `SoftDarkLaf.properties` 자동 로드 | 공식 방식, 클래스 이름으로 LaF 지정 가능 | 파일 이름이 클래스 이름에 묶임 → SDD 경로 `themes/soft-dark.properties` 와 다름 |
| (a') `SoftDarkLaf extends FlatDarkLaf` 가 `getAdditionalDefaults()`(protected, 3.8 에 있음)를 재정의해 `themes/soft-dark.properties` 를 읽음 | (a) 의 장점 + 경로 자유. 추가 기본값은 FlatLaf.properties → FlatDarkLaf.properties 다음에 합쳐져 `@background` 같은 기반 변수까지 덮어쓴다 | 클래스 하나 더(10줄) |
| (b) `FlatPropertiesLaf(name, InputStream)` + `@baseTheme=dark` | 클래스 없음 | 익명 LaF → `UIManager.setLookAndFeel(String)` · `FlatAnimatedLafChange` 로 바꿀 때 다시 만들 정보가 코드에 흩어짐 |
| (c) IntelliJ theme json | 테마 생태계 호환 | IntelliJ 키 체계로 한 번 더 변환됨, 우리 토큰과 1:1 이 아님 |

## 결정
- **(a')**. `tanager.ui.theme.SoftDarkLaf extends FlatDarkLaf`, `SoftLightLaf extends FlatLightLaf`(값만, 전환은 M5).
- 파일: `app/ui/src/main/resources/themes/soft-dark.properties` · `soft-light.properties`. 한 파일이 **유일한 색 원본**이다. 터미널 팔레트(전경 · 배경 · 커서 · 선택 · ANSI 16색)도 같은 파일에 `@term*` · `@ansi0`~`@ansi15` 변수로 둔다(코드에 색 값 없음).
- 파일 구조: ① 토큰 변수 `@window = #1B1E26` … ② FlatLaf 기반 변수 연결 `@background = @window`, `@foreground = @text`, `@accentColor = @accent`(OS 강조색 `systemColor(accent)` 무시), `@selectionBackground = @select`, `@selectionForeground = @text` ③ 컴포넌트 키 `TitlePane.background = @header`, `Component.borderColor = @border`, `Component.focusColor = @accent`, 모양 `Component.arc` · `Button.arc` · `TextComponent.arc` · `Popup.borderCornerRadius` = 8, `ScrollBar.thumbArc = 999` ④ 코드용 사용자 정의 키 `Tanager.<token> = @<token>`, `Tanager.pane.arc = 6`, `Tanager.focusBorder.width = 2`, `Tanager.term.*`.
- FlatLaf 는 잘못된 값을 예외 없이 로그만 남기고 `null` 로 둔다(3.8 에서 확인). 그래서 모든 `Tanager.*` 키가 null 이 아닌지 테스트로 막는다.
- **글꼴**: naver/d2-coding-font **VER1.4.0**(2026-10-03, OFL-1.1) 의 표준 빌드 `D2Coding/` Regular · Bold TTF 두 개를 수정 없이 커밋한다. 합자판 · TTC 는 쓰지 않는다(터미널은 합자를 쓰지 않고 `Font.createFont` 는 TTF 가 단순).
  - zip SHA-256 `17e2da5e2879006eb725b87943f7988736ef8b6c9f8b84bc8e3bdbcb8dd46744`(GitHub 자산 digest 와 같음)
  - `D2Coding-Ver1.4.0-20261003.ttf` `d5f2b6bf5b1826cfbf3734c70582f0cd42ea2e1d6c2636ae681b612bc9c5682f`
  - `D2CodingBold-Ver1.4.0-20261003.ttf` `14a0cfa4d96fbd976bc4b1a6ef81e51df353a1689d5273ebcd908c857a4f2752`
  - `OFL.txt` `1807e8dec4d65f474cbf9be39f5e2254ecb81702babc320749e272ea66ffcc69`
  - 위치 `app/ui/src/main/resources/fonts/`(+ `OFL.txt`, `SHA256SUMS`). `.gitattributes` 의 `*.ttf binary` 는 이미 있다.
  - 실행 시 `Font.createFont(TRUETYPE_FONT, …)` + `GraphicsEnvironment.registerFont` 로 등록해 이름 `"D2Coding"` 으로 찾는다(headless 에서도 동작 확인). 실패하면 `Monospaced` 로 띄우고 로그만 남긴다.
  - 고지: 루트 `THIRD-PARTY-NOTICES.md`. app-image 에는 jpackage `--app-content` 로 이 파일과 `licenses/D2Coding-OFL.txt`(ui 리소스의 OFL.txt 를 빌드가 복사)를 넣는다. 원본은 저장소에 하나만 둔다.

## 결과
- UI 코드는 색을 `ColorToken` 열거형 → `UIManager.getColor("Tanager.…")` 으로만 얻는다. `java.awt.Color` 생성은 `tanager.ui.theme` 에서만 한다(ArchUnit).
- 대비 검사는 Swing 없이 토큰 파일을 직접 파싱해 계산한다. FlatLaf 가 같은 값을 읽는지는 headless `getDefaults()` 로 한 번 더 확인한다.
- 글꼴을 바꾸거나 갱신하면 SHA256SUMS · 이 ADR · THIRD-PARTY-NOTICES 를 같은 PR 에서 바꾼다. 부분 집합 추출 등 수정본은 RFN 때문에 이름을 바꿔야 하므로 하지 않는다.
- 저장소가 약 8.5MB 커진다. Git LFS 는 쓰지 않는다(클론 · CI 설정이 늘어나는 비용이 더 크다).
- 구현에서 확인한 것(#45)
  - 글꼴 zip · ttf 2개 · OFL 의 SHA-256 이 위 고정값과 같다. zip 은 GitHub 자산 digest 와도 같다.
  - Windows 의 jpackage `--app-content` 는 `app/` 이 아니라 **이미지 루트(`Tanager.exe` 옆)** 에 풀린다: `Tanager\THIRD-PARTY-NOTICES.md`, `Tanager\licenses\D2Coding-OFL.txt`. `:bootstrap:verifyAppImage` 가 이것과 ui jar 안의 `fonts/` 를 검사한다(CI package-smoke).
  - FlatLaf 3.8 은 기본으로 `TitlePane.unifiedBackground = true` 라서 제목 표시줄을 window 색으로 칠한다. 시안 D 의 header 단을 보이려고 두 테마 모두 `false` 로 둔다.
  - headless 리눅스(CI)에서도 `registerBundled()` 와 한글 판정이 통과한다.
  - #22 에서 app-content 에 저장소의 `LICENSE`(MIT)를 `LICENSE.txt` 로 더했다. zip 과 MSI 설치 폴더 모두에 들어가고 `verifyAppImage` 가 검사한다(SDD §11).
  - #67 에서 저장소 루트 `licenses/`(제3자 라이선스 전문 · NOTICE)를 app-content 의 `licenses/` 에 함께 넣었다. D2Coding OFL 은 지금처럼 ui 리소스에서 복사되어 같은 폴더의 `licenses/D2Coding-OFL.txt` 에 있다. `verifyAppImage` 는 `THIRD-PARTY-NOTICES.md` 에 적힌 파일 · jar 목록과 이미지를 양쪽으로 대조한다.

## 개정 — ANSI 배경 위 글자 대비 보정 (#113, 2026-11-27)
- **문제**: WSL 에서 `/mnt/c` 아래 폴더를 `ls` 하면 GNU ls 기본 `ow=34;42`(파랑 글자 + 초록 배경)로 나온다. 소프트 팔레트에서 이 조합은 1.28 : 1 이라 글자가 안 보였다. `st=37;44` · `su=37;41` 도 1.3 : 1 안팎이다.
- **원인**: 소프트 팔레트는 글자색으로 쓰기 좋게 밝고 부드럽게 맞췄다. 그런데 같은 색을 배경으로 쓰면 글자와 배경이 모두 밝아진다.
- **결정**: 팔레트 토큰은 바꾸지 않는다. 그리는 순간 글자색만 보정한다(Windows Terminal 의 adjustIndistinguishableColors 와 같은 방식).
  - 보정 조건: 글자색과 배경색이 모두 ANSI 16색이고(글자색은 기본 전경 포함) 대비가 **4.5 : 1(WCAG AA)** 보다 낮을 때.
  - 보정 방법: 글자색을 배경과 더 대비되는 극(검정 · 흰색) 쪽으로 섞어 기준에 맞춘다(`ReadableForeground`, 결정적).
  - 보정하지 않는 경우: truecolor · 256색 · 기본 배경 · 반전(INVERSE). 사용자가 직접 고른 색으로 본다.
- **방식**: JediTerm 3.76 의 글자색 계산은 `TerminalPanel` 의 private 메서드라 끼어들 수 없다.
  - 그래서 SGR 마다 거치는 `StyleState.setCurrent` 를 바꾼 `ReadableStyleState` 를 위젯의 `createDefaultStyle()` 로 넣는다.
  - 에뮬레이터는 `getCurrent()` 에서 다음 스타일을 만든다. 보정이 쌓이지 않도록 보정한 색은 원래 색을 기억하는 `TerminalColor` 하위 타입으로 두고, 다음 `setCurrent` 에서 되돌린 뒤 다시 판단한다.
- **대안(버림)**: 배경용 색을 따로 두는 방법은 하나의 배경으로 해결되지 않는다.
  - 파랑 글자(`ow`)에는 어두운 배경이, 검정 글자(`tw=30;42`)에는 밝은 배경이 필요하다. 한 배경색으로는 두 조합을 함께 맞출 수 없다.

## 개정 — UI 글꼴은 논리 글꼴 Dialog (#125, 2026-11-27)
- **문제**: FlatLaf 는 시스템 UI 글꼴을 쓰는데, 한국어 Windows 에서는 맑은 고딕이다. 맑은 고딕은 U+005C(`\`)를 원화 기호 `₩` 로 그린다. 그래서 사이드바 · 머리줄 · 상태줄의 Windows 경로가 `C:₩work` 로 보였다.
- **결정**: 두 테마에서 `defaultFont = Dialog` 로 둔다. Dialog 는 라틴 글자를 라틴 글꼴로, 한글을 대체 글꼴로 그리는 합성(논리) 글꼴이다. 크기는 시스템 값을 그대로 쓴다. 터미널 글꼴(D2Coding)은 그대로다.
- **대안(버림)**
  - Segoe UI 같은 물리 글꼴: Java 는 물리 글꼴에서 글자 단위로 다른 글꼴을 대신 쓰지 않는다. 그래서 한글이 상자로 나온다.
  - 경로에만 D2Coding: 사이드바 · 상태줄이 고정폭이 되어 시안 D 와 어긋난다.
- **확인**: Windows JDK 오프스크린 렌더에서 `C:\work\infra · 한글 폴더` 가 그대로 보였다. `SoftLafDefaultsTest.uiFontIsLogicalDialog` 가 지킨다.
