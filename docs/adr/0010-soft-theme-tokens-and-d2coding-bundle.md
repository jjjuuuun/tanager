# 0010. 소프트 테마 구현 방식(FlatLaf 하위 클래스 + 토큰 파일)과 D2Coding 번들

- 상태: 채택(#45, 2026-11-25)
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
- **(a')**. `dev.tanager.ui.theme.SoftDarkLaf extends FlatDarkLaf`, `SoftLightLaf extends FlatLightLaf`(값만, 전환은 M5).
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
- UI 코드는 색을 `ColorToken` 열거형 → `UIManager.getColor("Tanager.…")` 으로만 얻는다. `java.awt.Color` 생성은 `dev.tanager.ui.theme` 에서만 한다(ArchUnit).
- 대비 검사는 Swing 없이 토큰 파일을 직접 파싱해 계산한다. FlatLaf 가 같은 값을 읽는지는 headless `getDefaults()` 로 한 번 더 확인한다.
- 글꼴을 바꾸거나 갱신하면 SHA256SUMS · 이 ADR · THIRD-PARTY-NOTICES 를 같은 PR 에서 바꾼다. 부분 집합 추출 등 수정본은 RFN 때문에 이름을 바꿔야 하므로 하지 않는다.
- 저장소가 약 8.5MB 커진다. Git LFS 는 쓰지 않는다(클론 · CI 설정이 늘어나는 비용이 더 크다).
- 구현에서 확인한 것(#45)
  - 글꼴 zip · ttf 2개 · OFL 의 SHA-256 이 위 고정값과 같다. zip 은 GitHub 자산 digest 와도 같다.
  - Windows 의 jpackage `--app-content` 는 `app/` 이 아니라 **이미지 루트(`Tanager.exe` 옆)** 에 풀린다: `Tanager\THIRD-PARTY-NOTICES.md`, `Tanager\licenses\D2Coding-OFL.txt`. `:bootstrap:verifyAppImage` 가 이것과 ui jar 안의 `fonts/` 를 검사한다(CI package-smoke).
  - FlatLaf 3.8 은 기본으로 `TitlePane.unifiedBackground = true` 라서 제목 표시줄을 window 색으로 칠한다. 시안 D 의 header 단을 보이려고 두 테마 모두 `false` 로 둔다.
  - headless 리눅스(CI)에서도 `registerBundled()` 와 한글 판정이 통과한다.
