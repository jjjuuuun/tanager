// 실행 모듈(tanager-app): application 실행 + jpackage app-image(jlink 로 필요한 모듈만)
plugins {
    id("tanager.java-conventions")
    application
}

val jvmFlags = listOf("--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8")

// jdeps 가 정적으로 못 잡는 모듈: 문자 집합(MS949 등 콘솔 코드 페이지), 화면 낭독기 연동(접근성)
val extraRuntimeModules = listOf("jdk.charsets", "jdk.accessibility")

application {
    applicationDefaultJvmArgs = jvmFlags
}

val launcher = javaToolchains.launcherFor(java.toolchain)
val installLib = layout.buildDirectory.dir("install/${project.name}/lib")
val modulesFile = layout.buildDirectory.file("jpackage/runtime-modules.txt")
val javaRelease = java.toolchain.languageVersion.map { it.asInt().toString() }

// 런타임에 넣을 모듈 계산: jdeps --print-module-deps 결과 + extraRuntimeModules → 파일로 남겨 리뷰할 수 있게
val computeRuntimeModules = tasks.register("computeRuntimeModules") {
    group = "distribution"
    description = "jdeps 로 app-image 에 넣을 Java 모듈 목록을 계산한다"
    dependsOn(tasks.named("installDist"))
    inputs.dir(installLib)
    outputs.file(modulesFile)
    doLast {
        val bin = launcher.get().metadata.installationPath.dir("bin").asFile
        val jdeps = listOf("jdeps.exe", "jdeps").map { File(bin, it) }.first { it.exists() }
        val jars = installLib.get().asFile.listFiles { f -> f.name.endsWith(".jar") }.orEmpty().sortedBy { it.name }
        val command = listOf(
            jdeps.path, "--print-module-deps", "--ignore-missing-deps",
            "--multi-release", javaRelease.get(),
            "--class-path", jars.joinToString(File.pathSeparator),
        ) + jars.map { it.path }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "jdeps 실패:\n$output" }
        val detected = output.lineSequence().last().split(',').map(String::trim).filter(String::isNotEmpty)
        val modules = (detected + extraRuntimeModules).toSortedSet()
        modulesFile.get().asFile.apply { parentFile.mkdirs() }.writeText(modules.joinToString(",") + "\n")
    }
}

// jpackage app-image: build/jpackage/Tanager/Tanager.exe (Windows)
tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "jpackage 로 실행 파일 폴더(app-image)를 만든다"
    dependsOn(computeRuntimeModules)
    val outDir = layout.buildDirectory.dir("jpackage")
    val mainJar = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    val mainClass = application.mainClass
    val appVersion = (findProperty("version")?.toString()?.takeIf { it != "unspecified" } ?: "0.0.0")
    inputs.dir(installLib)
    inputs.file(modulesFile)
    outputs.dir(outDir.map { it.dir("Tanager") })
    doFirst {
        delete(outDir.get().dir("Tanager"))
        val bin = launcher.get().metadata.installationPath.dir("bin").asFile
        val exe = listOf("jpackage.exe", "jpackage").map { File(bin, it) }.first { it.exists() }
        val args = mutableListOf(
            exe.path, "--type", "app-image", "--name", "Tanager",
            "--app-version", appVersion.substringBefore('-'),
            "--input", installLib.get().asFile.path, "--main-jar", mainJar.get(),
            "--main-class", mainClass.get(), "--dest", outDir.get().asFile.path,
            "--add-modules", modulesFile.get().asFile.readText().trim(),
        )
        jvmFlags.forEach { args += listOf("--java-options", it) }
        commandLine(args)
    }
}
