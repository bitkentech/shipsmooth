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
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")

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
val experimentalEnabled = (findProperty("experimental.enabled") as String?)?.toBoolean() ?: true
val pluginVersion = (findProperty("plugin.version") as String?) ?: "0.3.14"
val generateBuildConstants by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("src/main/java-templates"))
    into(layout.buildDirectory.dir("generated/sources/build-constants"))
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
// jlink build only (-PjlinkBuild): shade dagger + javax.inject into the core jar
// and re-inject module-info.class (Shadow, like Maven shade, strips it).
// Mirrors the Maven core jlink profile exactly: include com.google.dagger:dagger
// + javax.inject:javax.inject, drop signature files, then $SEMERU/bin/jar --update.
// ---------------------------------------------------------------------------
if (project.hasProperty("jlinkBuild")) {
    val semeruHome = (findProperty("jlink.exec.home") as String?)
        ?: "/opt/installers/jdk-semeru/jdk-25.0.2+10"

    tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        // Replace the plain core jar (no classifier) so the jlink module path
        // picks up the shaded jar in its place, exactly as Maven does.
        archiveClassifier.set("")
        // Shade ONLY dagger + javax.inject; everything else stays a module dep.
        dependencies {
            include(dependency("com.google.dagger:dagger:.*"))
            include(dependency("javax.inject:javax.inject:.*"))
        }
        // Drop signature files (Maven shade filter *.SF/*.DSA/*.RSA).
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    // Shadow strips module-info.class; restore it from the compiled classes using
    // the Semeru jar, so core stays a named JPMS module on the jlink module path.
    val reinjectModuleInfo by tasks.registering(Exec::class) {
        dependsOn("shadowJar")
        val shadedJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
            .flatMap { it.archiveFile }
        val classesDir = layout.buildDirectory.dir("classes/java/main")
        inputs.file(shadedJar)
        inputs.dir(classesDir)
        workingDir(classesDir)
        commandLine(
            "$semeruHome/bin/jar",
            "--update",
            "--file=${shadedJar.get().asFile.absolutePath}",
            "module-info.class",
        )
    }

    tasks.named("assemble") { dependsOn(reinjectModuleInfo) }
}
