plugins { id("tanager.java-conventions") }

// 모든 모듈의 클래스를 읽어 의존 방향 · 금지 import 를 ArchUnit 으로 검사한다(규칙은 후속 PR)
dependencies {
    testImplementation(libs.archunit)
    testImplementation(project(":tanager-domain"))
    testImplementation(project(":tanager-application"))
    testImplementation(project(":tanager-adapter-pty"))
    testImplementation(project(":tanager-adapter-persistence"))
    testImplementation(project(":tanager-adapter-toolkit-claude"))
    testImplementation(project(":tanager-platform-windows"))
    testImplementation(project(":tanager-platform-macos"))
    testImplementation(project(":tanager-ui-swing"))
    testImplementation(project(":tanager-app"))
}
