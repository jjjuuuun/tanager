plugins { id("tanager.app-conventions") }

// 조립 루트(composition root): 유일하게 모든 모듈을 안다 (ADR-0002)
dependencies {
    testImplementation(testFixtures(project(":application")))
    testImplementation(libs.awaitility)
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
