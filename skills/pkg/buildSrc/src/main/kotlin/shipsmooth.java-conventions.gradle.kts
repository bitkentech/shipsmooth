// Shared Java conventions for the skills:pkg Gradle trial (plan-71).
// Mirrors the relevant settings from the Maven parent/skills POM so the Gradle
// build is parity-comparable: Semeru/OpenJ9 25 toolchain, UTF-8 encoding,
// the project-local Maven repository, and JUnit Jupiter on the test classpath.

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
        // JDK 25, any vendor. skills/pkg only renders JTE templates and runs
        // tests — it has no jlink/SCC/OpenJ9 dependency, so the Semeru vendor
        // pin (needed by cli/packaging) would only over-constrain Phase 0.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
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
