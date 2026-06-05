plugins {
    // Enables precompiled Kotlin DSL convention plugins under
    // src/main/kotlin/*.gradle.kts (e.g. shipsmooth.java-conventions).
    `kotlin-dsl`
}

repositories {
    // Plugins applied by the convention scripts (e.g. the Gradle distribution's
    // own plugins) resolve from the Gradle Plugin Portal / mavenCentral.
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
}
