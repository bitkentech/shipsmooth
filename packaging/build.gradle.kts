plugins {
    id("shipsmooth.java-conventions")
}

dependencies {
    // packaging uses only io.bitken.ss.resources.Os (PackageRuntime) — now in the
    // tiny :plugin-model leaf module, not :skills:pkg (plan-79 Task 5). This is the
    // smell the split removes: packaging no longer pulls in the whole skills-
    // rendering module for one enum. jackson + commons-compress for manifest
    // reading and zip assembly.
    implementation(project(":plugin-model"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.apache.commons:commons-compress:1.27.1")
}

// ===========================================================================
// Packaging assembly + release entrypoints (porting packaging/pom.xml).
//
// Two kinds of work live here:
//  1. Payload assembly into the prod build/ tree (copy-dist, and — under the
//     claude profile — copy-scripts / copy-ts-source). These feed the payload
//     that ValidateRelease checks.
//  2. Release entrypoints: stringly-typed JavaExec wrappers over the existing
//     io.bitken.ss.dist.* mains (parity only — no type-safety gain). The Java
//     is left untouched; only its main()-bound paths are satisfied here.
//
// PublishRelease is WIRED but intentionally NOT invoked by any aggregate task:
// it publishes a GitHub release (outward-facing) and must be run explicitly.
// ===========================================================================

val repoRoot = rootProject.layout.projectDirectory
val repoRootPath = repoRoot.asFile.absolutePath
val pluginVersion = (findProperty("plugin.version") as String?) ?: "0.3.14"

// jlink target -> Semeru home, used by packageRuntime_<target> and publishRelease.
// The *target string* is the PackageRuntime argument: it drives both Os.fromPackagingTarget
// (only "win32"-prefixed -> WINDOWS) and the zip filename, so the windows entry MUST be
// "win32-x64" — not "windows-x64" — to match the Maven payload. The Semeru property keys
// below stay "windows-x64" (mirroring the pom's <jdk.semeru.windows-x64>).
val semeruByTarget = mapOf(
    "linux-x64" to "/opt/installers/jdk-semeru/jdk-25.0.2+10",
    "darwin-x64" to "/opt/installers/jdk-semeru-mac-x64/Contents/Home",
    "darwin-arm64" to "/opt/installers/jdk-semeru-mac-arm64/Contents/Home",
    "win32-x64" to "/opt/installers/jdk-semeru-win-x64/jdk-25.0.2+10",
)

// Maps a packageRuntime target back to the publish-release -Djdk.semeru.<key> property name.
fun semeruPropertyKey(target: String): String = if (target == "win32-x64") "windows-x64" else target

// build.outputDir is the prod payload root (defaults to <repo>/build, mirroring the pom's
// prod-profile <build.outputDir>). build-gemini/ is its gemini sibling.
val outputDir = (findProperty("build.outputDir") as String?)
    ?.let { file(it) }
    ?: repoRoot.dir("build").asFile
val geminiOutputDir = (findProperty("build.gemini.outputDir") as String?)
    ?.let { file(it) }
    ?: repoRoot.dir("build-gemini").asFile
val codexOutputDir = (findProperty("build.codex.outputDir") as String?)
    ?.let { file(it) }
    ?: repoRoot.dir("build-codex").asFile

// Note: the payload JS copy (copyDist, + copyDistProd) moved to skills/pkg in
// Task 21/23 — it assembles the plugin payload, not the jlink runtime.
// This module keeps only the runtime/release entrypoints below. outputDir /
// geminiOutputDir remain for validateRelease, which reads the assembled payloads.

// ---------------------------------------------------------------------------
// Release entrypoints
// ---------------------------------------------------------------------------

// Applies the repo-root + version system properties shared by every dist main.
fun JavaExec.withDistDefaults() {
    group = "release"
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("shipsmooth.repo.root", repoRootPath)
    systemProperty("project.version", pluginVersion)
}

// ValidateRelease: checks the assembled build/ + build-gemini/ payloads.
val validateRelease by tasks.registering(JavaExec::class) {
    description = "Validate the assembled prod build/ + build-gemini/ + build-codex/ payloads."
    withDistDefaults()
    mainClass.set("io.bitken.ss.dist.ValidateRelease")
    systemProperty("build.outputDir", outputDir.absolutePath)
    systemProperty("build.gemini.outputDir", geminiOutputDir.absolutePath)
    systemProperty("build.codex.outputDir", codexOutputDir.absolutePath)
}

// dev-profile guard: the staged jlink image (cli/target/jlink-image/bin/shipsmooth) must
// exist before a runtime is packaged. In Maven this was an antrun <fail> bound to the dev
// profile; here it is a per-target precondition that runs after staging and before the
// PackageRuntime JavaExec action.
fun Task.verifyJlinkImageStaged() = doFirst {
    val launcher = repoRoot.file("cli/target/jlink-image/bin/shipsmooth").asFile
    if (!launcher.exists()) {
        throw GradleException(
            "jlink image not found at ${launcher.path}. " +
                "Staging (:cli:image_<target>) did not produce a launcher.",
        )
    }
}

// PackageRuntime per platform: zips the jlink image + launcher into a
// shipsmooth-<ver>-<target>.zip. PackageRuntime.main hardcodes the image path
// repoRoot/cli/target/jlink-image (a Maven-ism), so each task first stages the
// Gradle image (cli/build/jlink-image-<target>) into that location. The Java
// class is left untouched for parity (its constructor already takes the path;
// only main() is path-bound).
semeruByTarget.forEach { (target, jdkHome) ->
    val stageImage = tasks.register<Copy>("stageImage_$target") {
        group = "release"
        description = "Stage the Gradle jlink image for $target into the Maven-expected path."
        dependsOn(":cli:image_$target")
        val dest = repoRoot.dir("cli/target/jlink-image")
        // jlink writes read-only legal/ files; a stale dest blocks overwrite, so
        // clear it first (chmod to make it deletable).
        doFirst {
            val d = dest.asFile
            if (d.exists()) {
                d.walkBottomUp().forEach { it.setWritable(true) }
                delete(d)
            }
        }
        from(repoRoot.dir("cli/build/jlink-image-$target"))
        into(dest)
    }
    tasks.register<JavaExec>("packageRuntime_$target") {
        description = "Stage + zip the $target runtime payload (shipsmooth-<ver>-$target.zip)."
        withDistDefaults()
        // stageImage populates cli/target/jlink-image; the guard (doFirst) then confirms
        // the launcher exists before PackageRuntime's JavaExec action runs.
        dependsOn(stageImage)
        verifyJlinkImageStaged()
        mainClass.set("io.bitken.ss.dist.PackageRuntime")
        args(target, jdkHome)
    }
}

// PublishRelease — WIRED ONLY. Never depended on by an aggregate task; run
// explicitly and deliberately (it publishes outward).
tasks.register<JavaExec>("publishRelease") {
    description = "Publish a GitHub release. Outward-facing — run only when intended."
    withDistDefaults()
    mainClass.set("io.bitken.ss.dist.PublishRelease")
    args((findProperty("shipsmooth.release.version") as String?) ?: pluginVersion)
    semeruByTarget.forEach { (target, home) ->
        systemProperty("jdk.semeru.${semeruPropertyKey(target)}", home)
    }
}
