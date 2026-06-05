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
        // Run on the JDK actually installed here (25, any vendor). Maven likewise
        // runs javac on whatever JDK is present and cross-compiles to 21 via
        // --release; we mirror that with options.release below rather than
        // requiring a separate JDK 21 install. Semeru is NOT a compile toolchain —
        // it enters only as jlink.exec.home for the jlink/jar exec steps.
        languageVersion.set(JavaLanguageVersion.of(25))
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
    // Exclude the JTE-generated template classes — they are generated code, not
    // hand-written logic, so measuring their coverage only dilutes the number.
    classDirectories.setFrom(
        classDirectories.files.map { dir ->
            fileTree(dir) { exclude("gg/jte/generated/**") }
        }
    )
}
