plugins {
    id("shipsmooth.java-conventions")
}

dependencies {
    // packaging uses io.bitken.ss.resources.Os from skills:pkg; jackson + commons-
    // compress for manifest reading and zip assembly. (The pom's claude dep is only
    // reactor ordering — no code dependency — so it's omitted here.)
    implementation(project(":skills:pkg"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.apache.commons:commons-compress:1.27.1")
}

// ---------------------------------------------------------------------------
// Release entrypoints (replacing the Maven exec-maven-plugin executions). These
// stay stringly-typed JavaExec-with-args — no type-safety gain, parity only.
// PublishRelease is WIRED but intentionally NOT invoked by any aggregate task:
// it is outward-facing (publishes a GitHub release) and must be run explicitly.
// ---------------------------------------------------------------------------
val repoRoot = rootProject.layout.projectDirectory.asFile.absolutePath
val pluginVersion = (findProperty("plugin.version") as String?) ?: "0.3.14"

val semeruByTarget = mapOf(
    "linux-x64" to "/opt/installers/jdk-semeru/jdk-25.0.2+10",
    "darwin-x64" to "/opt/installers/jdk-semeru-mac-x64/Contents/Home",
    "darwin-arm64" to "/opt/installers/jdk-semeru-mac-arm64/Contents/Home",
    "windows-x64" to "/opt/installers/jdk-semeru-win-x64/jdk-25.0.2+10",
)

// copy-dist: compiled JS (minus *.test.js) into build/dist/ alongside the
// session-start-config.json that Target renders.
val outputDir = (findProperty("build.outputDir") as String?)
    ?.let { file(it) }
    ?: rootProject.layout.projectDirectory.dir("build").asFile
val copyDist by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.dir("skills/pkg/scripts/dist")) {
        exclude("**/*.test.js")
    }
    into(File(outputDir, "dist"))
}

// ValidateRelease: checks the assembled build/ + build-gemini/ payloads.
val validateRelease by tasks.registering(JavaExec::class) {
    group = "release"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.bitken.ss.dist.ValidateRelease")
    systemProperty("build.outputDir", outputDir.absolutePath)
    systemProperty(
        "build.gemini.outputDir",
        (findProperty("build.gemini.outputDir") as String?)
            ?: rootProject.layout.projectDirectory.dir("build-gemini").asFile.absolutePath,
    )
}

// PackageRuntime per platform: zips the jlink image + launcher into a
// shipsmooth-<ver>-<target>.zip. PackageRuntime.main hardcodes the image path
// repoRoot/cli/target/jlink-image (a Maven-ism), so each task first stages the
// Gradle image (cli/build/jlink-image-<target>) into that location. The Java
// class is left untouched for parity (its constructor already takes the path;
// only main() is path-bound).
semeruByTarget.forEach { (target, jdkHome) ->
    val stageImage = tasks.register<Copy>("stageJlinkImage_$target") {
        dependsOn(":cli:jlinkImage_$target")
        val dest = rootProject.layout.projectDirectory.dir("cli/target/jlink-image")
        // jlink writes read-only legal/ files; a stale dest blocks overwrite, so
        // clear it first (chmod to make it deletable).
        doFirst {
            val d = dest.asFile
            if (d.exists()) {
                d.walkBottomUp().forEach { it.setWritable(true) }
                delete(d)
            }
        }
        from(rootProject.layout.projectDirectory.dir("cli/build/jlink-image-$target"))
        into(dest)
    }
    tasks.register<JavaExec>("packageRuntime_$target") {
        group = "release"
        dependsOn(stageImage)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("io.bitken.ss.dist.PackageRuntime")
        args(target, jdkHome)
        systemProperty("shipsmooth.repo.root", repoRoot)
        systemProperty("project.version", pluginVersion)
    }
}

// PublishRelease — WIRED ONLY. Never depended on by an aggregate task; run
// explicitly and deliberately (it publishes outward).
tasks.register<JavaExec>("publishRelease") {
    group = "release"
    description = "Publishes a GitHub release. Outward-facing — run only when intended."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.bitken.ss.dist.PublishRelease")
    args((findProperty("shipsmooth.release.version") as String?) ?: pluginVersion)
    systemProperty("shipsmooth.repo.root", repoRoot)
    semeruByTarget.forEach { (target, home) -> systemProperty("jdk.semeru.$target", home) }
}
