// 모든 Tanager 모듈 공통: Java 25 · UTF-8 · JUnit 6 · JaCoCo · Spotless(palantir)
plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()
fun ver(alias: String) = libs.findVersion(alias).get().requiredVersion

java {
    toolchain { languageVersion = JavaLanguageVersion.of(ver("java").toInt()) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
}

dependencies {
    testImplementation(platform(lib("junit-bom")))
    testImplementation(lib("junit-jupiter"))
    testImplementation(lib("assertj"))
    testImplementation(lib("jqwik"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    // -PexcludeTags=wsl,windows : 그 태그의 테스트를 뺀다(CI 러너엔 WSL 배포판이 없다)
    val excluded = (findProperty("excludeTags") as String?)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
        if (!excluded.isNullOrEmpty()) excludeTags(*excluded.toTypedArray())
    }
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8")
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports { xml.required = true; html.required = true }
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat(ver("palantir-java-format"))
        trimTrailingWhitespace()
        endWithNewline()
    }
}
