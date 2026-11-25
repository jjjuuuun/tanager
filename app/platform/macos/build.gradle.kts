plugins { id("tanager.java-conventions") }

// M7 에서 채운다. 지금은 의존 방향만 고정.
dependencies {
    implementation(project(":application"))
}
