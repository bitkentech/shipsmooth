// Shared Java conventions for the skills:pkg Gradle trial (plan-71).
// Mirrors the relevant settings from the Maven parent/skills POM so the Gradle
// build is parity-comparable: Semeru/OpenJ9 25 toolchain, UTF-8 encoding,
// the project-local Maven repository, and JUnit Jupiter on the test classpath.

plugins {
    `java-library`
}

repositories {
    // Project uses /opt/mvn/repository as its local Maven repo (see ~/.m2/settings.xml);
    // mavenLocal() honours that via the standard repository resolution.
    mavenLocal()
    mavenCentral()
}

java {
    toolchain {
        // Semeru/OpenJ9 25 at /opt/installers/jdk-semeru/jdk-25.0.2+10.
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.IBM)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    val junitVersion = "5.10.2" // matches skills/pkg/pom.xml
    "testImplementation"(platform("org.junit:junit-bom:$junitVersion"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // skills:pkg has no module-info; keep it off the module path (parity with Maven).
    modularity.inferModulePath.set(false)
}
