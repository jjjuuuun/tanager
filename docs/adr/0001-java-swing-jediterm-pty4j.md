# 0001. 언어·터미널 스택: Java 25 + Swing + JediTerm + pty4j

- 상태: 채택 (M0 스파이크에서 검증 후 확정)
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
