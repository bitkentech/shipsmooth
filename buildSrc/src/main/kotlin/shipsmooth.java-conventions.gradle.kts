// Shared Java conventions for all shipsmooth modules (plan-71 migration).
// Mirrors the Maven parent POM so the Gradle build is parity-comparable: Java 21
// vendor-agnostic toolchain (Maven compiles at maven.compiler.target=21 with no
// vendor pin — Semeru/OpenJ9 is only an exec'd jlink/packaging binary, not the
// compiler), UTF-8 encoding, the project-local Maven repo, and JUnit Jupiter.

plugins {
    `java-library`
    jacoco // coverage report for the test task
}

repositories {
    // Project uses /opt/mvn/repository as its local Maven repo (see ~/.m2/settings.xml);
    // mavenLocal() honours that via the standard repository resolution.
    mavenLocal()
    mavenCentral()
}

java {
    toolchain {
        // Compile/test on JDK 25, cross-compiling to 21 via options.release below
        // (mirrors Maven's javac --release). Semeru is NOT a compile toolchain — it
        // enters only as jlink.exec.home for the jlink/jar exec steps.
        //
        // This box has TWO JDK 25 installs: stock OpenJDK (Oracle) and Semeru/OpenJ9
        // (IBM). With no vendor pin, both satisfy languageVersion(25) and Gradle's
        // tie-break decides — which is non-deterministic across environments. Pin the
        // vendor to Oracle so compile+test always run on stock OpenJDK and Semeru is
        // reserved strictly for jlink, making the intended split explicit rather than
        // incidental. (plan-98 finding.)
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ORACLE)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Bytecode parity with maven.compiler.target=21: cross-compile to 21 while
    // running on the installed JDK 25 toolchain (matches Maven's javac --release).
    options.release.set(21)
}

dependencies {
    val junitVersion = "5.10.2" // matches skills/pkg/pom.xml
    "testImplementation"(platform("org.junit:junit-bom:$junitVersion"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    // Gradle 9 no longer puts the platform launcher on the test runtime
    // classpath transitively; declare it explicitly or the executor can't start.
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // skills:pkg has no module-info; keep it off the module path (parity with Maven).
    modularity.inferModulePath.set(false)
    // Always (re)generate the coverage report after tests run.
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    // Exclude generated code (JTE templates, JAXB classes, Dagger components, the
    // Build constants) — these are generated, not hand-written logic, so measuring
    // their coverage only dilutes the number.
    classDirectories.setFrom(
        classDirectories.files.map { dir ->
            fileTree(dir) {
                exclude(
                    "gg/jte/generated/**",      // JTE precompiled templates (skills)
                    "io/bitken/ss/jaxb/**",     // xjc-generated JAXB classes (core)
                    "**/Dagger*.class",         // Dagger-generated components (core)
                    "io/bitken/ss/Build.class", // templated Build constants (core)
                )
            }
        }
    )
}
