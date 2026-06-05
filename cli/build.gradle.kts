plugins {
    id("shipsmooth.java-conventions")
    application
}

dependencies {
    implementation(project(":core"))
    implementation("info.picocli:picocli:4.7.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
}

application {
    // Launcher entrypoint — matches the jlink launcher
    // shipsmooth=io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth.
    mainClass.set("io.bitken.ss.cli.Shipsmooth")
}

// ---------------------------------------------------------------------------
// jlink images + SCC launcher (-PjlinkBuild). Mirrors the Maven cli jlink profile:
// a hand-pinned runtime module path (with the SHADED core jar in place of the
// plain one), 5 platform images via the Linux jlink + platform jmods, the OpenJ9
// SCC launcher, and smoke tests run through it.
// ---------------------------------------------------------------------------
if (project.hasProperty("jlinkBuild")) {
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

    // The shaded core jar (Task 13) must replace the plain core jar on the module
    // path, exactly as the Maven jlink profile pins ${core.jar} to the shaded jar.
    // Referenced by path + string task dependency to avoid cross-project typed
    // task access (the Shadow type isn't on cli's build classpath).
    val coreProject = project(":core")
    val shadedCoreJarTask = "${coreProject.path}:reinjectModuleInfo"
    val shadedCoreJarFile = coreProject.layout.buildDirectory.file("libs/core.jar")

    // Runtime module path: cli jar + shaded core jar + the cli runtime-classpath
    // dependency jars (picocli, jaxb, jackson, jakarta.* — the same set Maven
    // hand-pins from the local repo), minus the plain core jar.
    fun runtimeModulePath(): String {
        val cliJar = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val core = shadedCoreJarFile.get().asFile
        val depJars = configurations.named("runtimeClasspath").get().files
            .filterNot { it.name.startsWith("core") } // drop plain core jar
        return (listOf(cliJar, core) + depJars).joinToString(":") { it.absolutePath }
    }

    platformJmods.forEach { (platform, jmods) ->
        tasks.register<Exec>("jlinkImage_$platform") {
            dependsOn("jar", shadedCoreJarTask)
            val outDir = layout.buildDirectory.dir("jlink-image-$platform")
            outputs.dir(outDir)
            doFirst { delete(outDir) }
            commandLine(
                "$semeruHome/bin/jlink",
                "--module-path", "${runtimeModulePath()}:$jmods",
                "--add-modules", "io.bitken.ss.cli,openj9.sharedclasses",
                "--launcher", "shipsmooth=io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth",
                "--no-header-files", "--no-man-pages",
                "--compress", "zip-9",
                "--output", outDir.get().asFile.absolutePath,
            )
        }
    }

    // OpenJ9 SCC launcher: a shell wrapper running the full JRE with shared-class
    // cache + quickstart, on the runtime module path. (TODO cross-platform — Maven
    // has the same TODO; linux/posix only for now.)
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
}
