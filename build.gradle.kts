// 루트: 모듈 공통 설정은 build-logic 의 convention plugin 에 있다. 여기는 집계 태스크만.

// CI test-core 가 부르는 집계 태스크: 커버리지 하한이 있는 모듈(domain · application)의 검증을 모은다
tasks.register("jacocoCoverageVerification") {
    group = "verification"
    description = "커버리지 하한 검증(domain 90% · application 80%)"
    dependsOn(":domain:jacocoTestCoverageVerification", ":application:jacocoTestCoverageVerification")
}
