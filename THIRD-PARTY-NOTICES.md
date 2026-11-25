# 제3자 고지 (Third-Party Notices)

Tanager 는 아래 제3자 구성 요소를 포함하거나 실행 시 함께 배포한다. 각 구성 요소는 표시한 라이선스(SPDX ID)를 따른다.
라이선스 전문 동봉은 #22 에서 정리한다. 지금은 D2Coding 의 OFL 전문만 함께 배포한다.

## 글꼴

### D2Coding 1.4.0 (Regular · Bold)
- 라이선스: `OFL-1.1` (SIL Open Font License 1.1)
- 저작권: Copyright (c) 2015, NAVER Corporation (https://www.navercorp.com), with Reserved Font Names D2Coding, D2Coding-Bold.
- 출처: https://github.com/naver/d2-coding-font (릴리스 `VER1.4.0`, `D2Coding/` 표준 빌드 TTF 두 개를 수정 없이 포함)
- 번들 위치: 소스 `app/ui/src/main/resources/fonts/`(`tanager-ui` jar 안 `fonts/`), 라이선스 전문 `fonts/OFL.txt`.
  실행 파일 폴더(app-image)와 MSI 설치 폴더에는 `licenses/D2Coding-OFL.txt` 로도 들어간다.
- 무결성: `fonts/SHA256SUMS`, 결정 기록 `docs/adr/0010-soft-theme-tokens-and-d2coding-bundle.md`.

## 라이브러리 (실행 클래스패스)

| 구성 요소 | 버전 | SPDX | 저작권 |
|---|---|---|---|
| FlatLaf (`com.formdev:flatlaf`) | 3.8 | `Apache-2.0` | Copyright 2019 FormDev Software GmbH |
| JediTerm (`org.jetbrains.jediterm:jediterm-core` · `jediterm-ui`) | 3.76 | `LGPL-3.0-only OR Apache-2.0` | Copyright JetBrains s.r.o. |
| pty4j (`org.jetbrains.pty4j:pty4j`) | 0.14.0 | `EPL-1.0` | Copyright JetBrains s.r.o. and pty4j contributors |
| JNA (`net.java.dev.jna:jna` · `jna-platform`) | 5.19.1 | `Apache-2.0 OR LGPL-2.1-or-later` | Copyright (c) Timothy Wall and JNA contributors |
| Jackson (`tools.jackson.core:jackson-core` · `jackson-databind`, `com.fasterxml.jackson.core:jackson-annotations`) | 3.3.0 · 2.23 | `Apache-2.0` | Copyright 2007-, Tatu Saloranta and FasterXML, LLC |
| SLF4J (`org.slf4j:slf4j-api` · `slf4j-jdk14`) | 2.0.19 | `MIT` | Copyright (c) 2004-2023 QOS.ch |
| Kotlin 표준 라이브러리 (`org.jetbrains.kotlin:kotlin-stdlib`) | 2.4.20 | `Apache-2.0` | Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors |
| JetBrains Java Annotations (`org.jetbrains:annotations`) | 26.1.0 | `Apache-2.0` | Copyright 2000-2025 JetBrains s.r.o. |

JediTerm 은 LGPL-3.0 과 Apache-2.0 이중 라이선스이며(Maven POM 에는 LGPL 3.0 만 적혀 있다), Tanager 는 jar 를 수정하지 않고 그대로 함께 배포한다.

## 실행 환경

app-image 에는 jlink 로 줄인 Java 런타임(Eclipse Temurin/OpenJDK 25)이 들어간다. 라이선스: `GPL-2.0-only WITH Classpath-exception-2.0`. 런타임 고지는 app-image 의 `runtime/legal/` 에 있다.
