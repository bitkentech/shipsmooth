// Plan-98 spike: a throwaway Quarkus web module. NOT a shipped host — no auth, not
// wired into any release/packaging path. Deleting this module must leave core/cli
// fully working (see plan-98 Non-goals).
//
// Deliberately does NOT apply shipsmooth.java-conventions: Quarkus drives its own
// dependency/BOM and test wiring, and this spike stays off the JPMS module path
// (no module-info.java) to sidestep the Quarkus + JPMS friction the plan flagged.
// It mirrors the repo conventions by hand instead: JDK 25 toolchain, cross-compile
// to 21, UTF-8, mavenLocal() + mavenCentral().

plugins {
    java
    // Quarkus Gradle plugin — resolved from the Gradle Plugin Portal. Provides
    // quarkusDev (dev mode) and quarkusBuild. Pinned to the 3.33 LTS line
    // (production-recommended; 12 months of fixes) rather than the latest release.
    id("io.quarkus") version "3.33.2"
}

repositories {
    // Project-local Maven repo (/opt/mvn/repository via ~/.m2/settings.xml) then Central.
    mavenLocal()
    mavenCentral()
}

java {
    toolchain {
        // Match the other modules: JDK 25 pinned to Oracle (stock OpenJDK). This box
        // also has Semeru/OpenJ9 25 (IBM); without the vendor pin either could win the
        // toolchain tie-break. Semeru is reserved for jlink only — validating Quarkus
        // on OpenJ9 is deferred (plan-98). Cross-compile to 21 handled below.
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ORACLE)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Bytecode parity with the rest of the build (maven.compiler.target=21).
    options.release.set(21)
}

val quarkusPlatformVersion = "3.33.2"

dependencies {
    // Quarkus BOM aligns all Quarkus artifact versions.
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:$quarkusPlatformVersion"))

    // Minimal REST stack to serve a page. quarkus-rest is the Jakarta REST
    // (JAX-RS) runtime; arc is the CDI container Quarkus needs to boot.
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-arc")

    testImplementation("io.quarkus:quarkus-junit5")
}
