plugins { id("tanager.java-conventions") }

// 모든 모듈의 클래스를 읽어 의존 방향 · 금지 import 를 ArchUnit 으로 검사한다(규칙은 후속 PR)
dependencies {
    testImplementation(libs.archunit)
    // 위반 픽스처(UI 가 pty4j 를 직접 쓰는 경우)를 컴파일하려고
    testImplementation(libs.pty4j)
    // 위반 픽스처(ui 가 JediTerm 색을 직접 만드는 경우)를 컴파일하려고
    testImplementation(libs.jediterm.core)
    testImplementation(project(":domain"))
    testImplementation(project(":application"))
    testImplementation(project(":adapter:pty"))
    testImplementation(project(":adapter:persistence"))
    testImplementation(project(":adapter:toolkit:claude"))
    testImplementation(project(":platform:windows"))
    testImplementation(project(":platform:macos"))
    testImplementation(project(":ui"))
    testImplementation(project(":bootstrap"))
}
