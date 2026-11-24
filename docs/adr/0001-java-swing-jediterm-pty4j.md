# 0001. 언어·터미널 스택: Java 25 + Swing + JediTerm + pty4j

- 상태: 채택 — M0 스파이크(#10, 2026-11-24)로 확정
- 날짜: 2026-11-24
- 관련: SDD §3, §9

## 맥락
분할 가능한 여러 터미널을 한 창에 띄우고, 좌측에 작업 경로 트리를 둔다. Windows(ConPTY 기반 WSL·Windows PowerShell 5.1·cmd — PowerShell 7 은 범위 밖, 2026-11-24 사용자 결정)부터 지원하고 이후 macOS 까지 넓힌다. 언어는 Python 또는 Java 중 최신 버전으로 한다. 가장 어려운 부분은 **터미널 에뮬레이션**(VT 시퀀스, 색, 와이드 문자, IME)과 **PTY**(Windows ConPTY, Unix pty)다.

## 선택지
1. **Java 25 + Swing(FlatLaf) + JediTerm + pty4j**
   - 장점: JediTerm·pty4j 는 JetBrains IDE 내장 터미널에 쓰이는 검증된 조합. Windows/macOS/Linux 를 한 API 로 지원. jpackage 로 JRE 포함 `.exe/.msi/.dmg` 생성. record·sealed·패턴 매칭·가상 스레드로 함수형 코어 작성이 쉬움.
   - 단점: Swing UI 의 현대적 외관은 FlatLaf 로 보완해야 함. 배포 크기(약 60~90MB, jlink 로 축소).
2. **Python 3.14+ + Textual(TUI) + pyte + pywinpty/ptyprocess**
   - 장점: 개발 속도, 터미널 안에서 도는 순수 TUI.
   - 단점: 터미널 안에서 터미널을 에뮬레이션해야 함(tmux 를 새로 만드는 수준). pyte 는 순수 Python 이라 대량 출력 시 느림, IME·와이드 문자 처리 취약. Windows 와 Unix 의 PTY 라이브러리가 달라 추상화 비용 증가. 단일 실행 파일(PyInstaller/Nuitka) 패키징 시 백신 오탐 이슈.
3. Python + PySide6(Qt): Qt 용 크로스플랫폼 터미널 위젯이 없음(QTermWidget 은 Linux 전용) → 에뮬레이터 직접 구현 필요.

## 결정
1번. 프로젝트의 핵심 가치(경로 이력·레이아웃·복원)에 집중하고, 터미널 에뮬레이션·PTY 는 검증된 라이브러리에 맡긴다.
JDK 는 **최신 LTS(25)** 로 고정한다. 비 LTS(26·27·28)는 건너뛰고, 다음 LTS(29, 2027-09 예정)가 나오면 올린다(Gradle toolchain 한 줄 변경). 그 사이에는 25.0.x 보안 업데이트만 반영한다. preview 기능은 쓰지 않는다.

## 결과
- M0 스파이크 검증 항목: (a) pty4j 가 Windows 에서 ConPTY 로 wsl.exe·powershell.exe(5.1)·cmd 를 띄우는가 (b) JediTerm 에서 한글 IME 조합·와이드 문자 표시 (c) OSC 7 / OSC 9;9 가 스트림에서 가로채지는가 (d) jpackage app-image 실행.
- 검증 실패 시 이 ADR 을 `대체됨` 으로 바꾸고 대안을 새 ADR 로 기록한다.

## M0 검증 결과 (2026-11-24, 이슈 #10, 실험 코드: 태그 `archive/spike-10-conpty-jediterm` 의 `spike/m0`)
환경: Windows 11, Windows 측 JDK 17(Gradle 실행용) · WSL Ubuntu-24.04. 자동 확인은 `--selftest`(화면 없이 셸 3개 기동 → 결과 파일), 한글 입력은 사람이 확인.

| 항목 | 결과 | 비고 |
|---|---|---|
| (a) ConPTY 로 셸 3개 기동 | ✅ | pty4j **0.14.0** 이 `conpty.dll` 을 jar 에 번들해 `%TEMP%\pty4j-win-x86-64-*` 에 풀어 쓴다 → 설치 경로가 읽기 전용이어도 됨. 프로세스 클래스 `WinConPtyProcess`(winpty 아님) |
| (a) 창 크기 전달 | ✅ | bash `tput` · PowerShell `$Host.UI.RawUI` · cmd 하위 프로세스 모두 100×30. cmd 의 `mode con` 은 ConPTY 아래에서 아무것도 출력하지 않음 |
| 한글 입출력(UTF-8) | ✅ | 셸 3개 모두 입력 에코 · 출력 왕복 정상. ConPTY 가 콘솔 코드 페이지(949)와 무관하게 UTF-8 로 준다 |
| (b) 한글 IME · 와이드 문자 | ✅ 조건부 | **기본 글꼴에 한글 글리프가 있어야 한다.** JediTerm 3.76 은 글자 단위 대체 글꼴이 없다(`getFontToDisplay` 는 굵게 · 기울임만). 대체를 덧씌우면 확정 글자는 되지만 **IME 조합 중 글자는 private `drawInputMethodUncommitedChars` 가 기본 글꼴로 그려 네모가 된다.** D2Coding 은 조합 · 확정 · 두 칸 폭 모두 정상 |
| (e) Java 25 toolchain | ✅ | Gradle **9.9.2** + foojay 1.0.0 이 Temurin 25.0.5 를 자동으로 받음(설치 불필요) |
| (d) jpackage app-image | ✅ | exe 로 같은 자가 진단 통과. 전체 런타임 포함 132MB → M1 에서 jlink 모듈 축소 |
| `wsl --list --quiet` | ✅ | UTF-16LE 디코딩으로 배포판 이름 정상 |

### 이 결과로 정한 것
- 버전: pty4j **0.14.x**(Maven Central), JediTerm **3.76**(Maven Central 에 없음 → `https://packages.jetbrains.team/maven/p/ij/intellij-dependencies` 저장소 추가), Gradle 9.9.2, foojay 1.0.0.
- **터미널 기본 글꼴 = D2Coding(SIL OFL 1.1, 앱에 번들)**. 설정에서 한글 글리프가 없는 글꼴(`Font.canDisplay('한') == false`)을 고르면 경고하고, 그래도 쓰면 확정 글자만 대체 글꼴로 그린다(조합 중 글자는 깨질 수 있음을 안내).
- Java 25 에서 JNA 의 `System.load` 가 restricted method 경고를 내므로 실행 옵션에 `--enable-native-access=ALL-UNNAMED` 를 넣는다(jpackage `--java-options` · `application` 둘 다).
- 남은 위험: 업스트림 JediTerm 이 조합 중 글자에 대체 글꼴을 쓰도록 패치 기여할지 M2 에서 판단.
