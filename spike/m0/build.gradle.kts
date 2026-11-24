plugins {
    application
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories {
    mavenCentral()
    // JediTerm 은 Maven Central 에 없고 JetBrains 저장소에만 있다
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
}

dependencies {
    implementation("org.jetbrains.pty4j:pty4j:0.14.0")
    implementation("org.jetbrains.jediterm:jediterm-core:3.76")
    implementation("org.jetbrains.jediterm:jediterm-ui:3.76")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass = "dev.tanager.spike.SpikeApp"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

// 확인 항목 d: jpackage app-image (Windows exe + 번들 런타임)
val jpackageImage by tasks.registering(Exec::class) {
    group = "distribution"
    dependsOn(tasks.installDist)
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val libDir = layout.buildDirectory.dir("install/tanager-spike-m0/lib")
    val outDir = layout.buildDirectory.dir("jpackage")
    doFirst {
        delete(outDir)
        val bin = launcher.get().metadata.installationPath.dir("bin").asFile
        val exe = if (File(bin, "jpackage.exe").exists()) File(bin, "jpackage.exe") else File(bin, "jpackage")
        commandLine(
            exe.path, "--type", "app-image", "--name", "TanagerSpike",
            "--input", libDir.get().asFile.path, "--main-jar", "tanager-spike-m0.jar",
            "--main-class", "dev.tanager.spike.SpikeApp", "--dest", outDir.get().asFile.path,
            "--java-options", "-Dfile.encoding=UTF-8",
        )
    }
}
