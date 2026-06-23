plugins {
    id("shipsmooth.java-conventions")
    application
}

dependencies {
    implementation(project(":core"))
    implementation("info.picocli:picocli:4.7.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.17.2")
}

application {
    // Launcher entrypoint — matches the jlink launcher
    // shipsmooth=io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth.
    mainClass.set("io.bitken.ss.cli.Shipsmooth")
}

// ---------------------------------------------------------------------------
// jlink images + SCC launcher. Mirrors the Maven cli jlink profile: a hand-pinned
// runtime module path (with the SHADED core jar in place of the plain one), 5
// platform images via the Linux jlink + platform jmods, the OpenJ9 SCC launcher,
// and smoke tests run through it.
//
// Registered UNCONDITIONALLY (no -PjlinkBuild gate). All jar/classpath resolution
// is deferred to execution time (lazy argumentProviders for the Exec tasks; doLast
// for writeSccLauncher), so merely registering these tasks resolves nothing at
// config time — a normal `./gradlew build` neither resolves the runtime classpath
// for jlink nor pulls in core's shaded jar. They enter the graph only when a jlink
// image / launcher / smoke task is explicitly requested.
// ---------------------------------------------------------------------------
val semeruHome = (findProperty("jlink.exec.home") as String?)
    ?: "/opt/installers/jdk-semeru/jdk-25.0.2+10"
val jreHome = (findProperty("jlink.jre.home") as String?)
    ?: "/opt/installers/jre-semeru/jdk-25.0.2+10-jre"
val pluginVersion = (findProperty("plugin.version") as String?) ?: "0.3.14"

val platformJmods = mapOf(
    "linux-x64" to "$semeruHome/jmods",
    "darwin-x64" to "/opt/installers/jdk-semeru-mac-x64/Contents/Home/jmods",
    "darwin-arm64" to "/opt/installers/jdk-semeru-mac-arm64/Contents/Home/jmods",
    "windows-x64" to "/opt/installers/jdk-semeru-win-x64/jdk-25.0.2+10/jmods",
)

// The shaded core jar (core:reinjectModuleInfo, classifier "jlink" => core-jlink.jar)
// replaces the plain core jar on the module path, exactly as the Maven jlink profile
// pins ${core.jar} to the shaded jar. It is a SEPARATE file from the plain core.jar
// (which :cli:compileJava reads) — that separation is what avoids the overlapping-
// output collision (plan-74 Task 8). Referenced by path + string task dependency to
// avoid cross-project typed task access (the Shadow type isn't on cli's build classpath).
val coreProject = project(":core")
val shadedCoreJarTask = "${coreProject.path}:reinjectModuleInfo"
val shadedCoreJarFile = coreProject.layout.buildDirectory.file("libs/core-jlink.jar")

// Runtime module path: cli jar + shaded core jar + the cli runtime-classpath
// dependency jars (picocli, jaxb, jackson, jakarta.* — the same set Maven
// hand-pins from the local repo), minus the plain core jar. MUST be called only at
// execution time (inside argumentProviders / doLast): it resolves the jar task and
// the runtimeClasspath configuration, which would force eager resolution on every
// build if invoked at config time.
fun runtimeModulePath(): String {
    val cliJar = tasks.named<Jar>("jar").get().archiveFile.get().asFile
    val core = shadedCoreJarFile.get().asFile
    val depJars = configurations.named("runtimeClasspath").get().files
        .filterNot { it.name.startsWith("core") } // drop plain core jar
    return (listOf(cliJar, core) + depJars).joinToString(":") { it.absolutePath }
}

// A prod build (build.env=prod — the single signal, see buildSrc BuildEnv.kt) bakes
// EXPERIMENTAL_BUILD=false (hiding --enable-experimental from --help, via core) AND
// lands the jlink image in a prod-specific folder (jlink-image-<platform>-prod), so
// the release reads only its own artifact and can never reuse a stale dev image —
// clean provenance by path. The SAME build.env drives both the baked constant and
// this suffix, so they cannot disagree.
val imageDirSuffix = if (isProdBuild()) "-prod" else ""

platformJmods.forEach { (platform, jmods) ->
    tasks.register<Exec>("image_$platform") {
        dependsOn("jar", shadedCoreJarTask)
        // Track the jars this image packs as INPUTS, not just dependsOn ordering. The
        // image is assembled from runtimeModulePath() (cli jar + shaded core-jlink.jar +
        // runtime dep jars). dependsOn only orders the build — it does NOT make jlink
        // re-run when a jar's CONTENTS change. A version bump rewrites Build.class inside
        // core-jlink.jar without touching any task-graph input, so without this the image
        // stays UP-TO-DATE and ships a stale VERSION (caught by the release guard,
        // plan-75). ClasspathNormalizer ignores jar timestamps/order so only real content
        // changes bust it.
        inputs.files(
            tasks.named<Jar>("jar").flatMap { it.archiveFile },
            shadedCoreJarFile,
            configurations.named("runtimeClasspath"),
        ).withNormalizer(ClasspathNormalizer::class.java)
        val outDir = layout.buildDirectory.dir("jlink-image-$platform$imageDirSuffix")
        outputs.dir(outDir)
        doFirst { delete(outDir) }
        executable = "$semeruHome/bin/jlink"
        // Lazy command line — runtimeModulePath() (jar + runtimeClasspath
        // resolution) runs at execution time, not when the task is registered.
        argumentProviders.add {
            listOf(
                "--module-path", "${runtimeModulePath()}:$jmods",
                "--add-modules", "io.bitken.ss.cli,openj9.sharedclasses",
                "--launcher", "shipsmooth=io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth",
                "--no-header-files", "--no-man-pages",
                "--compress", "zip-9",
                "--output", outDir.get().asFile.absolutePath,
            )
        }
    }
}

// OpenJ9 SCC launcher: a shell wrapper running the full JRE with shared-class
// cache + quickstart, on the runtime module path. (TODO cross-platform — Maven
// has the same TODO; linux/posix only for now.) runtimeModulePath() is invoked
// inside doLast, so it stays execution-time only.
val writeSccLauncher by tasks.registering {
    dependsOn("jar", shadedCoreJarTask)
    val launcher = layout.buildDirectory.file("scc-launcher/shipsmooth")
    outputs.file(launcher)
    doLast {
        val sccDir = layout.buildDirectory.dir("scc").get().asFile.absolutePath
        val f = launcher.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            #!/bin/sh
            SCC_DIR="$sccDir"
            mkdir -p "${'$'}SCC_DIR"
            exec $jreHome/bin/java \
              -Xquickstart \
              -Xshareclasses:name=shipsmooth_v$pluginVersion,cacheDir="${'$'}SCC_DIR",nonfatal \
              --module-path ${runtimeModulePath()} \
              -m io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth "${'$'}@"
            """.trimIndent() + "\n"
        )
        f.setExecutable(true)
    }
}

// Smoke tests through the SCC launcher (Maven verify-phase equivalents).
val jlinkSmokeHelp by tasks.registering(Exec::class) {
    dependsOn(writeSccLauncher)
    commandLine(layout.buildDirectory.file("scc-launcher/shipsmooth").get().asFile.absolutePath, "--help")
}
val jlinkSmokeShow by tasks.registering(Exec::class) {
    dependsOn(writeSccLauncher)
    workingDir(rootProject.projectDir)
    commandLine(
        layout.buildDirectory.file("scc-launcher/shipsmooth").get().asFile.absolutePath,
        "plan", "show", "--plan", "27",
    )
}

// store first-run round-trip through the modular runtime (plan-87 Task 1). Catches the
// conf.ds JPMS-opens defect that classpath unit tests miss: `store init` must serialize
// StandaloneConfig and `store info` must deserialize it when the CLI runs as a real
// module. The script isolates XDG_CONFIG_HOME so it never touches the real ~/.config.
val jlinkSmokeStore by tasks.registering(Exec::class) {
    dependsOn(writeSccLauncher)
    commandLine(
        "sh",
        layout.projectDirectory.file("src/jlinkSmoke/store-roundtrip.sh").asFile.absolutePath,
        layout.buildDirectory.file("scc-launcher/shipsmooth").get().asFile.absolutePath,
    )
}
