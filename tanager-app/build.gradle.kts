plugins { id("tanager.app-conventions") }

// 조립 루트(composition root): 유일하게 모든 모듈을 안다 (ADR-0002)
dependencies {
    implementation(project(":tanager-application"))
    implementation(project(":tanager-ui-swing"))
    runtimeOnly(project(":tanager-adapter-pty"))
    runtimeOnly(project(":tanager-adapter-persistence"))
    runtimeOnly(project(":tanager-adapter-toolkit-claude"))
    runtimeOnly(project(":tanager-platform-windows"))
    runtimeOnly(project(":tanager-platform-macos"))
}

application {
    mainClass = "dev.tanager.app.TanagerApp"
}
