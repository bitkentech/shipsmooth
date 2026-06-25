plugins {
    id("shipsmooth.java-conventions")
    // JAXB xjc: generates io.bitken.ss.jaxb.* from plan-tasks.xsd, replacing the
    // Maven jaxb2-maven-plugin xjc execution.
    id("com.github.bjornvester.xjc") version "1.8.2"
    // Shadow: shades dagger + javax.inject into the core jar for the jlink build
    // (mirrors the Maven jlink-profile maven-shade-plugin). Only wired under
    // -PjlinkBuild; harmless to apply otherwise.
    id("com.gradleup.shadow") version "8.3.5"
}

dependencies {
    // jakarta.xml.bind-api is part of core's PUBLIC API: TaskStore returns
    // io.bitken.ss.jaxb.PlanTasks and throws JAXBException, so consumers (cli)
    // need it transitively -> api, not implementation. jaxb-runtime is the impl,
    // so it stays implementation.
    api("jakarta.xml.bind:jakarta.xml.bind-api:4.0.2")
    implementation("org.glassfish.jaxb:jaxb-runtime:4.0.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    // jakarta.inject is part of core's PUBLIC API: AppComponents hands out
    // Provider<TaskStore>/Provider<PlanService> so cli command leaves can be built
    // without a settled state root (the locator is touched only at Provider.get()).
    // Consumers (cli) need it transitively -> api, not implementation.
    api("jakarta.inject:jakarta.inject-api:2.0.1")

    // Dagger: `requires static dagger` in module-info is a compile/module concern,
    // but the generated DaggerAppComponents needs dagger.internal.* on the RUNTIME
    // classpath (tests run on the classpath, not the module path). So dagger is a
    // normal runtime dependency here, not compileOnly. The compiler runs as an
    // annotation processor generating DaggerAppComponents (Maven annotationProcessorPaths).
    implementation("com.google.dagger:dagger:2.59.2")
    annotationProcessor("com.google.dagger:dagger-compiler:2.59.2")
}

xjc {
    // plan-tasks.xsd -> io.bitken.ss.jaxb (matches jaxb2-maven-plugin packageName).
    xsdDir.set(layout.projectDirectory.dir("src/main/resources"))
    defaultPackage.set("io.bitken.ss.jaxb")
    useJakarta.set(true) // Jakarta JAXB (jakarta.xml.bind), not legacy javax
}

// Build constants (VERSION, EXPERIMENTAL_BUILD) — replaces templating-maven-plugin
// filter-sources. Expand the @-token template into a generated source dir and add
// it to the main source set.
// Experimental visibility derives from the single build.env signal (plan-75 Task 2).
// The rule ("what build.env means") lives once in buildSrc BuildEnv.kt; every module
// calls it rather than re-reading the property, so the derivation can't drift.
val experimentalEnabled = experimentalEnabled()
// Fail loud rather than stamp a stale literal: a release that silently bakes the
// wrong VERSION is exactly the failure plan-75 exists to kill. gradle.properties
// always supplies plugin.version, so this only fires on a real misconfiguration.
val pluginVersion = (findProperty("plugin.version") as String?)?.takeIf { it.isNotBlank() }
    ?: throw GradleException(
        "plugin.version is not set — refusing to generate Build constants. " +
        "Set it in gradle.properties or pass -Pplugin.version=<version>."
    )
val generateBuildConstants by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("src/main/java-templates"))
    into(layout.buildDirectory.dir("generated/sources/build-constants"))
    // expand() values are NOT part of a Copy task's default up-to-date check —
    // only the source files are fingerprinted. Declare them as explicit inputs so
    // that flipping experimental.enabled or bumping plugin.version invalidates the
    // task (and the build-cache key) and regenerates Build.java. Without this a
    // stale Build.java ships — the 0.3.17 prod leak (see plan-75 Defect 1).
    inputs.property("experimentalEnabled", experimentalEnabled)
    inputs.property("pluginVersion", pluginVersion)
    // The template uses Maven-style dotted tokens (${experimental.enabled},
    // ${project.version}). Gradle's expand() is Groovy SimpleTemplateEngine, which
    // reads those as nested property access, so supply nested maps rather than
    // flat dotted keys.
    expand(
        "experimental" to mapOf("enabled" to experimentalEnabled),
        "project" to mapOf("version" to pluginVersion),
    )
}
sourceSets.main {
    java.srcDir(generateBuildConstants)
}

// ---------------------------------------------------------------------------
// jlink shading: shade dagger + javax.inject into the core jar and re-inject
// module-info.class (Shadow, like Maven shade, strips it). Mirrors the Maven core
// jlink profile exactly: include com.google.dagger:dagger + javax.inject:javax.inject,
// drop signature files, then $SEMERU/bin/jar --update.
//
// Registered UNCONDITIONALLY (no -PjlinkBuild gate). Lazy configuration means this
// is zero-cost unless something pulls reinjectModuleInfo into the graph (cli's
// image_* tasks). It is deliberately NOT wired into `assemble`/`build`, so a
// normal `./gradlew build` neither shades nor runs the Semeru jar tool — the only
// consumer is the cli jlink build, which dependsOn this task directly.
// ---------------------------------------------------------------------------
val semeruHome = (findProperty("jlink.exec.home") as String?)
    ?: "/opt/installers/jdk-semeru/jdk-25.0.2+10"

val classesDir = layout.buildDirectory.dir("classes/java/main")

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    // Emit the shaded jar under a DISTINCT name (core-jlink.jar) rather than
    // overwriting the plain core.jar. Overwriting collided with :cli:compileJava,
    // which reads the plain core.jar via the project(":core") dependency: once jlink
    // was un-gated, Gradle flagged the shared output as an undeclared cross-task
    // dependency ("uses this output … without declaring a dependency"). cli's
    // runtimeModulePath() substitutes this classified jar onto the jlink module path
    // in place of the plain one (plan-74 Task 8).
    archiveClassifier.set("jlink")
    // Shade ONLY dagger + javax.inject; everything else stays a module dep.
    dependencies {
        include(dependency("com.google.dagger:dagger:.*"))
        include(dependency("javax.inject:javax.inject:.*"))
    }
    // Drop signature files (Maven shade filter *.SF/*.DSA/*.RSA).
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// Shadow strips module-info.class; restore it from the compiled classes with
// the Semeru jar so core stays a named JPMS module on the jlink module path.
// The shaded jar is declared as this task's OUTPUT (the task updates it in
// place): when shadowJar reruns and replaces the jar, Gradle sees the output
// changed out from under it and re-runs the re-injection — no stale stamp.
val reinjectModuleInfo by tasks.registering(Exec::class) {
    dependsOn("shadowJar")
    val shadedJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
        .flatMap { it.archiveFile }
    inputs.file(classesDir.map { it.file("module-info.class") })
    outputs.file(shadedJar)
    workingDir(classesDir)
    // Lazy command line — argumentProviders defers the shadedJar path resolution
    // to execution time, so merely registering this task resolves no jar at config
    // time (the eager `shadedJar.get()` would have run on every `./gradlew build`).
    executable = "$semeruHome/bin/jar"
    argumentProviders.add {
        listOf(
            "--update",
            "--file=${shadedJar.get().asFile.absolutePath}",
            "module-info.class",
        )
    }
}
