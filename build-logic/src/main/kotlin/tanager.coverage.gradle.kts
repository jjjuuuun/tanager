// 커버리지 하한이 있는 모듈(domain 90% · application 80%). 하한은 모듈 디렉터리의 gradle.properties 의 coverageMinimum.
plugins {
    id("tanager.java-conventions")
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    val minimum = (findProperty("coverageMinimum") as String? ?: "0.0").toBigDecimal()
    violationRules {
        rule { limit { counter = "LINE"; this.minimum = minimum } }
    }
}

tasks.named("check") { dependsOn(tasks.named("jacocoTestCoverageVerification")) }
