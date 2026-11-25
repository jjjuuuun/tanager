plugins { id("tanager.java-conventions") }

dependencies {
    implementation(project(":application"))
    implementation(libs.flatlaf)
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
}
