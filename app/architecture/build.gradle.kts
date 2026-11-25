plugins { id("tanager.java-conventions") }

// 모든 모듈의 클래스를 읽어 의존 방향 · 금지 import 를 ArchUnit 으로 검사한다(규칙은 후속 PR)
dependencies {
    testImplementation(libs.archunit)
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
