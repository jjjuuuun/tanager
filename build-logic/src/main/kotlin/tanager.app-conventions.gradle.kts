// 실행 모듈(app/bootstrap): application 실행 + jpackage app-image(jlink 로 필요한 모듈만)
import java.util.zip.ZipFile

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

// app-image 에 함께 넣을 고지: 루트 THIRD-PARTY-NOTICES.md + D2Coding OFL(원본은 ui 리소스 하나, ADR-0010)
val appContentDir = layout.buildDirectory.dir("jpackage-content")
val noticesFile = rootProject.layout.projectDirectory.file("THIRD-PARTY-NOTICES.md")
val d2CodingOfl = rootProject.layout.projectDirectory.file("app/ui/src/main/resources/fonts/OFL.txt")
val prepareAppContent = tasks.register<Sync>("prepareAppContent") {
    group = "distribution"
    description = "jpackage --app-content 로 넣을 고지 파일을 모은다"
    from(noticesFile)
    from(d2CodingOfl) {
        into("licenses")
        rename { "D2Coding-OFL.txt" }
    }
    into(appContentDir)
}

// jpackage app-image: build/jpackage/Tanager/Tanager.exe (Windows)
val imageDir = layout.buildDirectory.dir("jpackage/Tanager")
val jpackageImage = tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "jpackage 로 실행 파일 폴더(app-image)를 만든다"
    dependsOn(computeRuntimeModules, prepareAppContent)
    val outDir = layout.buildDirectory.dir("jpackage")
    val mainJar = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
    val mainClass = application.mainClass
    val appVersion = (findProperty("version")?.toString()?.takeIf { it != "unspecified" } ?: "0.0.0")
    inputs.dir(installLib)
    inputs.file(modulesFile)
    inputs.dir(appContentDir)
    outputs.dir(imageDir)
    doFirst {
        delete(imageDir.get())
        val bin = launcher.get().metadata.installationPath.dir("bin").asFile
        val exe = listOf("jpackage.exe", "jpackage").map { File(bin, it) }.first { it.exists() }
        val args = mutableListOf(
            exe.path, "--type", "app-image", "--name", "Tanager",
            "--app-version", appVersion.substringBefore('-'),
            "--input", installLib.get().asFile.path, "--main-jar", mainJar.get(),
            "--main-class", mainClass.get(), "--dest", outDir.get().asFile.path,
            "--add-modules", modulesFile.get().asFile.readText().trim(),
            // 경로마다 이름 그대로 이미지 루트(Windows: Tanager/ 바로 아래, Tanager.exe 옆)에 복사된다
            "--app-content", listOf("THIRD-PARTY-NOTICES.md", "licenses")
                .joinToString(",") { appContentDir.get().file(it).asFile.path },
        )
        jvmFlags.forEach { args += listOf("--java-options", it) }
        commandLine(args)
    }
}

// app-image 에 고지 · 글꼴이 빠지지 않았는지 검사한다(CI package-smoke). 위치는 jpackage 가 정하므로 재귀로 찾는다
tasks.register("verifyAppImage") {
    group = "verification"
    description = "app-image 안에 제3자 고지 · D2Coding 글꼴과 OFL 이 들어 있는지 검사한다"
    dependsOn(jpackageImage)
    inputs.dir(imageDir)
    doLast {
        val root = imageDir.get().asFile
        val files = root.walkTopDown().filter { it.isFile }.toList()
        val problems = mutableListOf<String>()
        for (name in listOf("THIRD-PARTY-NOTICES.md", "D2Coding-OFL.txt")) {
            val found = files.filter { it.name == name }
            if (found.isEmpty()) problems += "$name 없음"
            found.forEach { logger.lifecycle("ok  ${it.relativeTo(root)}") }
        }
        val uiJars = files.filter { it.name.startsWith("tanager-ui") && it.name.endsWith(".jar") }
        if (uiJars.size != 1) problems += "tanager-ui*.jar 가 1개가 아님: ${uiJars.map { it.relativeTo(root) }}"
        uiJars.firstOrNull()?.let { jar ->
            val entries = ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
            val fonts = entries.filter { it.startsWith("fonts/") && it.endsWith(".ttf") }
            if (fonts.size != 2) problems += "${jar.name} 안 fonts/*.ttf 가 2개가 아님: $fonts"
            if ("fonts/OFL.txt" !in entries) problems += "${jar.name} 안 fonts/OFL.txt 없음"
            logger.lifecycle("ok  ${jar.relativeTo(root)}: $fonts")
        }
        check(problems.isEmpty()) { "app-image 검사 실패(${root}):\n" + problems.joinToString("\n") { "  - $it" } }
    }
}
