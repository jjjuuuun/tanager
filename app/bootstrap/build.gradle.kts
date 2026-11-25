plugins { id("tanager.app-conventions") }

// 조립 루트(composition root): 유일하게 모든 모듈을 안다 (ADR-0002)
dependencies {
    testImplementation(testFixtures(project(":application")))
    testImplementation(libs.awaitility)
    // Windows 스모크 테스트가 JediTerm 위젯 · 화면 버퍼를 직접 본다(ui 는 jediterm 을 implementation 으로만 노출)
    testImplementation(libs.jediterm.ui)
    testImplementation(libs.jediterm.core)
    implementation(project(":application"))
    implementation(project(":ui"))
    // 조립 루트가 직접 만든다(Pty4jLauncher · JsonConfigRepository) — 플랫폼 · toolkit 은 ServiceLoader 라 runtimeOnly
    implementation(project(":adapter:pty"))
    implementation(project(":adapter:persistence"))
    runtimeOnly(project(":adapter:toolkit:claude"))
    runtimeOnly(project(":platform:windows"))
    runtimeOnly(project(":platform:macos"))
}

application {
    mainClass = "dev.tanager.bootstrap.TanagerApp"
}
