// Plan-98: the Quarkus web host. A shipped, CLI-launched host (`shipsmooth web serve`)
// packaged as a fast-jar payload sibling to the CLI jlink image. No auth (deferred).
// Deleting this module must still leave core/cli fully working.
//
// Deliberately does NOT apply shipsmooth.java-conventions: Quarkus drives its own
// dependency/BOM and test wiring, and this module stays off the JPMS module path
// (no module-info.java) — the Quarkus fast-jar is a classpath artifact, not a JPMS
// module. It mirrors the repo conventions by hand instead: JDK 25 toolchain,
// cross-compile to 21, UTF-8, mavenLocal() + mavenCentral().
//
// jlink module contract (see webJlinkModules below): because the fast-jar is
// non-modular, the CLI's minimized jlink image cannot run it as-is (it lacks
// jdk.unsupported etc.). Rather than the CLI hardcoding web's needs, WEB declares the
// JDK modules it requires here, and cli's image_<platform> unions them with its own
// root. The list was derived empirically (jdeps --print-module-deps on an uber-jar —
// the fast-jar breaks jdeps, cf. Quarkus #32034 — plus reflective blind-spots that
// jdeps can't see: crypto providers, service loaders). A jlinkSmoke boot test guards
// it: if a future Quarkus/dependency bump needs a module not listed here, the build
// fails loudly instead of shipping a web payload that can't start.

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

// The JDK modules the Quarkus fast-jar needs at runtime — WEB's own declared module
// deps, consumed by cli's image_<platform> to union into the shared jlink image.
// Rather than the CLI hardcoding web's needs, web declares them here and the build
// composes; a jlinkSmoke boot test guards against drift.
//
// DERIVATION (do not hand-edit without re-deriving — a wrong list ships a web host
// that can't start):
//   1. Base: `jdeps --ignore-missing-deps --print-module-deps --multi-release 25
//      <uber-jar>`. The fast-jar breaks jdeps (Quarkus #32034), so analysis runs on an
//      uber-jar. jdeps returned: java.base, java.desktop, java.logging, java.management,
//      java.naming, java.rmi, java.transaction.xa, jdk.compiler, jdk.unsupported.
//   2. Pruned jdeps FALSE POSITIVES (static refs in never-run code paths), each proven
//      droppable by booting the fast-jar on an image without it (HTTP 200):
//        - java.desktop, jdk.compiler  (AWT / hot-reload compiler — dev-only paths)
//        - java.rmi                    (only for JMX-over-RMI; we expose no remote mgmt)
//        - java.transaction.xa         (XA/distributed txns; we have no datasource)
//      Re-derive if a datasource or remote-management feature is added.
//   3. Added jdeps BLIND SPOTS — crypto providers load reflectively via service
//      loaders, invisible to static analysis AND to a non-TLS boot test. Kept as
//      insurance (first HTTPS use would else fail at runtime with no build signal):
//        - jdk.crypto.ec, jdk.crypto.cryptoki
//      Plus service-loader/filesystem/locale reflective needs: jdk.zipfs, jdk.localedata.
// Proven: fast-jar boots + serves HTTP 200 on a CLI+web union image built from this set.
val webJlinkModules = listOf(
    "java.logging", "java.management", "java.naming", "jdk.unsupported",
    "jdk.crypto.ec", "jdk.crypto.cryptoki", "jdk.zipfs", "jdk.localedata",
)
// Publish for cross-project consumption (cli's image_<platform> reads this to union
// web's modules into the shared jlink image alongside io.bitken.ss.cli).
extra["webJlinkModules"] = webJlinkModules

dependencies {
    // Quarkus BOM aligns all Quarkus artifact versions.
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:$quarkusPlatformVersion"))

    // Minimal REST stack to serve a page. quarkus-rest is the Jakarta REST
    // (JAX-RS) runtime; arc is the CDI container Quarkus needs to boot.
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-arc")

    testImplementation("io.quarkus:quarkus-junit5")
}
