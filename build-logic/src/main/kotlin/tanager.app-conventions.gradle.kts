// 실행 모듈(tanager-app): application 실행 + jpackage app-image
plugins {
    id("tanager.java-conventions")
    application
}

val jvmFlags = listOf("--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8")

application {
    applicationDefaultJvmArgs = jvmFlags
}

// jpackage app-image: build/jpackage/Tanager/Tanager.exe (Windows). 런타임 모듈 축소(jlink)는 후속 작업.
tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "jpackage 로 실행 파일 폴더(app-image)를 만든다"
    dependsOn(tasks.named("installDist"))
    val launcher = javaToolchains.launcherFor(java.toolchain)
    val installLib = layout.buildDirectory.dir("install/${project.name}/lib")
    val outDir = layout.buildDirectory.dir("jpackage")
    val mainJar = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    val mainClass = application.mainClass
    val appVersion = (findProperty("version")?.toString()?.takeIf { it != "unspecified" } ?: "0.0.0")
    inputs.dir(installLib)
    outputs.dir(outDir)
    doFirst {
        delete(outDir)
        val bin = launcher.get().metadata.installationPath.dir("bin").asFile
        val exe = listOf("jpackage.exe", "jpackage").map { File(bin, it) }.first { it.exists() }
        val args = mutableListOf(
            exe.path, "--type", "app-image", "--name", "Tanager",
            "--app-version", appVersion.substringBefore('-'),
            "--input", installLib.get().asFile.path, "--main-jar", mainJar.get(),
            "--main-class", mainClass.get(), "--dest", outDir.get().asFile.path,
        )
        jvmFlags.forEach { args += listOf("--java-options", it) }
        commandLine(args)
    }
}
