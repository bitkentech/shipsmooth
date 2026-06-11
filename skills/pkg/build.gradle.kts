import com.github.gradle.node.npm.task.NpmInstallTask
import com.github.gradle.node.npm.task.NpmTask
import org.gradle.api.tasks.PathSensitivity.RELATIVE

plugins {
    // Convention plugin from buildSrc: Semeru 25 toolchain, UTF-8, mavenLocal,
    // JUnit Jupiter. The JTE and render wiring is added in later plan-71 tasks.
    id("shipsmooth.java-conventions")
    // node-gradle: real content-hashed input/output tracking for the npm/tsc
    // pipeline, replacing the brittle `dist -nt tasks/session-start.ts` mtime
    // hack (a correctness fix — see docs/proposals/build-migrate.md §2).
    id("com.github.node-gradle.node") version "7.1.0"
    // gg.jte.gradle: precompile the staged .jte templates straight into
    // compileJava, collapsing the antrun + jte-maven + build-helper chain.
    id("gg.jte.gradle") version "3.1.15"
}

dependencies {
    // jte + jackson are needed both by the generated template classes and by
    // the Target/SkillRenderer code in src/main/java (parity with pom.xml).
    implementation("gg.jte:jte:3.1.15")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

node {
    // System Node (v18 on this box) — do not download a Node distribution,
    // matching the exec-maven-plugin behaviour in pom.xml.
    download.set(false)
    nodeProjectDir.set(file("scripts"))
}

// `npm install`, keyed on package-lock.json -> node_modules. node-gradle runs in
// nodeProjectDir (scripts/), so no per-task workingDir is needed.
tasks.named<NpmInstallTask>("npmInstall") {
    inputs.file("scripts/package-lock.json").withPathSensitivity(RELATIVE)
    outputs.dir("scripts/node_modules")
}

// `npm run build` (tsc + esbuild bundle). Hashing the whole scripts/tasks tree
// plus the build config kills the single-sentinel-file staleness bug the old
// mtime check had: edit any source and the rebuild fires; touch alone does not.
val compileTs by tasks.registering(NpmTask::class) {
    dependsOn(tasks.named<NpmInstallTask>("npmInstall"))
    args.set(listOf("run", "build"))

    inputs.dir("scripts/tasks").withPathSensitivity(RELATIVE)
    inputs.file("scripts/package.json").withPathSensitivity(RELATIVE)
    inputs.file("scripts/tsconfig.json").withPathSensitivity(RELATIVE)
    outputs.dir("scripts/dist")
}

// `npm test` (tsc -p tsconfig.test.json + bundle-test + node --test) — the TS unit
// suite under scripts/src/test (session-start / install-download / download-file).
// Neither Maven nor the earlier Gradle build ran it, so it would rot post-cutover;
// wire it into `check` so ./gradlew check (or build) executes it and FAILS the build
// on a broken test. (plan-71 Task 19.) Inputs cover the test sources + the production
// tasks they import + the test tsconfig, so it is incremental (UP-TO-DATE on no change).
val testTs by tasks.registering(NpmTask::class) {
    dependsOn(tasks.named<NpmInstallTask>("npmInstall"))
    args.set(listOf("test"))

    inputs.dir("scripts/src/test").withPathSensitivity(RELATIVE)
    inputs.dir("scripts/tasks").withPathSensitivity(RELATIVE)
    inputs.file("scripts/package.json").withPathSensitivity(RELATIVE)
    inputs.file("scripts/tsconfig.json").withPathSensitivity(RELATIVE)
    inputs.file("scripts/tsconfig.test.json").withPathSensitivity(RELATIVE)
    outputs.dir("scripts/dist-test")
}

// Fail `check`/`build` if the TS suite fails (recommended in the plan).
tasks.named("check") { dependsOn(testTs) }

// plan-76: lint the static POSIX bootstrap script. `sh -n` (syntax) always runs;
// `shellcheck` runs only when installed (stock CI/dev boxes may lack it), so the
// build never hard-fails on a missing optional linter but enforces it where present.
val installScript = layout.projectDirectory.file("src/main/resources/install-shipsmooth.sh")
val lintInstallScript by tasks.registering(Exec::class) {
    description = "Syntax-check install-shipsmooth.sh (sh -n always; shellcheck if available)."
    group = "verification"
    inputs.file(installScript)
    // Marker output keeps the task UP-TO-DATE when the script is unchanged.
    val marker = layout.buildDirectory.file("lint/install-shipsmooth.ok")
    outputs.file(marker)
    val scriptPath = installScript.asFile.path
    val markerPath = marker.get().asFile.path
    commandLine("sh", "-c",
        "set -e; sh -n \"$scriptPath\"; " +
        "if command -v shellcheck >/dev/null 2>&1; then shellcheck -s sh \"$scriptPath\"; " +
        "else echo 'shellcheck not installed; ran sh -n only'; fi; " +
        "mkdir -p \"$(dirname \"$markerPath\")\"; : > \"$markerPath\"")
}
tasks.named("check") { dependsOn(lintInstallScript) }

// Reproduce the antrun rename step: copy the sibling start/, experimental/,
// shared/ trees from the repo's skills/ dir into a staging root, renaming
// *.jte.md -> *.jte. The start/experimental/shared prefix is preserved so the
// generated template names match the literals in SkillRenderer
// (e.g. "start/SKILL.jte") and the gg.jte.generated.precompiled.* packages
// match the Maven output.
val skillsDir = layout.projectDirectory.dir("..") // skills/pkg -> skills/
val jteSrcDir = layout.buildDirectory.dir("jte-src") // single source of truth
val stageJte by tasks.registering(Copy::class) {
    listOf("start", "experimental", "shared").forEach { dir ->
        from(skillsDir.dir(dir)) { into(dir) }
    }
    rename("(.*)\\.jte\\.md", "$1.jte")
    into(jteSrcDir)
}

jte {
    // Generate .java into build/generated-sources/jte and wire them into the
    // main source set's compileJava — mirroring the Maven jte-maven-plugin
    // `generate` goal + build-helper add-source (so the generated artifacts and
    // gg.jte.generated.precompiled.* packages match the Maven output for parity).
    // Resolve the staged dir lazily (provider) rather than eagerly at config time.
    sourceDirectory.set(jteSrcDir.map { it.asFile.toPath() })
    contentType.set(gg.jte.ContentType.Plain)
    generate()
}

// generateJte reads the staged .jte tree, so it must run after stageJte.
tasks.named("generateJte") { dependsOn(stageJte) }

// ---------------------------------------------------------------------------
// Render targets (replacing the Maven dev / gemini-dev profiles).
//
// Each target is a JavaExec running io.bitken.ss.resources.Target with a full
// RenderSpec tuple. Modelling the variables as one RenderSpec (in buildSrc)
// keeps the two targets from drifting — the proposal's #1 correctness risk.
// ---------------------------------------------------------------------------

// plugin.version mirrors Maven's @project.version@. Sourced from a gradle
// property so it stays in lockstep with the Maven <version> (0.3.14).
val pluginVersion = (findProperty("plugin.version") as String?)
    ?: error("plugin.version must be set (gradle.properties) to match the Maven project version")
// skills:pkg is a subproject; the repo root is two levels up
// (skills/pkg -> skills -> repo root), not one.
val repoRoot = layout.projectDirectory.dir("../..")

// Dev jlinkDir resolves LAZILY from the cli jlink image for THIS build host. Using
// the task's output dir (not a hardcoded path) establishes the producer->consumer
// edge: requesting a dev render/install pulls in :cli:image_<host>, so the
// image is built automatically and the path is always correct. HostPlatform.tag()
// (buildSrc) matches detectPlatform() in session-start.ts. The .map keeps it lazy —
// the cli task is only resolved when the render actually runs (see jvmArgumentProviders
// in registerRender), so a normal build never pulls jlink into the graph.
val cliProject = project(":cli")
// Ensure :cli is configured before we look up its jlink task by name — the
// image_* tasks are registered in cli's build script, and cross-project
// tasks.named() resolves against the target project's already-evaluated task
// container. Without this, skills:pkg can evaluate first and the lookup fails.
evaluationDependsOn(":cli")
val hostTag = HostPlatform.tag()
val devJlinkDir: Provider<String> =
    cliProject.tasks.named("image_$hostTag")
        .map { it.outputs.files.singleFile.path }

// Wrap a constant in a provider so the non-dev variants share devJlinkDir's
// Provider<String> shape (RenderSpec.jlinkDir is a Provider across all variants).
fun constJlink(value: String): Provider<String> = provider { value }

// Resolve a render output dir: -Pbuild.outputDir overrides (so an assembleX task
// can target the shared build/ payload tree), else the per-variant default under
// the module build dir (back-compat for standalone renderX runs). (Task 21)
fun renderOutputDir(variantDefault: String): String =
    (findProperty("build.outputDir") as String?)
        ?: layout.buildDirectory.dir("render/$variantDefault").get().asFile.path

val claudeDevSpec = RenderSpec(
    buildPlatform = "claude",
    buildOs = "posix",
    buildEnv = BuildEnv.DEV,
    pluginBaseName = "shipsmooth",
    pluginVersion = pluginVersion,
    pluginDescription = "Agent coding workflow (dev build)",
    pluginSkillStartBasename = "start",
    skillFrontmatter = "",
    jlinkDir = devJlinkDir,
    pluginRepoName = "shipsmooth",
    outputDir = renderOutputDir("claude-dev"),
    pluginHookCommand = "node \"\${CLAUDE_PLUGIN_ROOT}/dist/session-start.js\"",
    // ObjectFactory for RenderSpec's independent constant providers. The .copy()
    // chain below (gemini-dev, prod, windows) inherits this same instance.
    objects = objects,
)

val geminiDevSpec = claudeDevSpec.copy(
    buildPlatform = "gemini",
    skillFrontmatter = """
        ---
        name: start-dev
        description: Use when starting any task — applies the shipsmooth agent coding workflow (dev build).
        ---
    """.trimIndent(),
    // Honour -Pbuild.outputDir like claudeDev, so assembleGeminiDev can target the
    // shared payload tree (Task 22). Standalone runs default to build/render/gemini-dev.
    outputDir = renderOutputDir("gemini-dev"),
    pluginHookCommand = "node \"\${extensionPath}/dist/session-start.js\"",
)

fun registerRender(taskName: String, spec: RenderSpec) =
    tasks.register<JavaExec>(taskName) {
        group = "render"
        description = "Render the ${spec.buildPlatform}-${spec.buildEnv.value} plugin variant via Target."
        dependsOn(tasks.named("compileJava"), compileTs)

        val runtimeClasspath = sourceSets["main"].runtimeClasspath
        classpath = runtimeClasspath
        mainClass.set("io.bitken.ss.resources.Target")

        val props = spec.systemProperties() // Map<String, Provider<String>>
        // -D system properties resolved at EXECUTION time via jvmArgumentProviders,
        // so registering the render never forces a provider. This matters for the dev
        // variant: its jlinkDir provider is the cli jlink task output, so an eager
        // resolve here would pull :cli:image_<host> into every build's graph.
        jvmArgumentProviders.add {
            props.map { (key, value) -> "-D$key=${value.get()}" }
        }

        // Inputs: the render is a pure function of (a) the RenderSpec tuple and
        // (b) the runtime classpath — which carries the compiled JTE template
        // classes, so a .jte.md edit (-> stageJte -> generateJte -> compileJava)
        // busts this task. With these declared, an unchanged render is
        // UP-TO-DATE instead of re-running every invocation. inputs.property
        // accepts the Provider directly; Gradle resolves it lazily for the check.
        props.forEach { (key, value) -> inputs.property(key, value) }
        inputs.files(runtimeClasspath).withNormalizer(ClasspathNormalizer::class.java)
        // Declare what the render OWNS at the right granularity (Task 21, Bazel-style):
        // the skills/ and hooks/ subtrees (variant-dependent file sets) plus the single
        // dist/session-start-config.json. It deliberately does NOT own all of dist/ —
        // copyDist owns the JS there — so the overlap-check can tell them apart and the
        // shared dist/ dir has one writer per file.
        outputs.dir("${spec.outputDir}/skills")
        outputs.dir("${spec.outputDir}/hooks")
        outputs.file("${spec.outputDir}/dist/session-start-config.json")
    }

val renderClaudeDev = registerRender("renderClaudeDev", claudeDevSpec)
val renderGeminiDev = registerRender("renderGeminiDev", geminiDevSpec)

// ---------------------------------------------------------------------------
// Prod render variants (Task 18) — mirror the Maven `prod`, `gemini`, `windows`
// profiles. Prod deltas vs dev: buildEnv=prod, experimentalEnabled=false,
// prod description, empty/prod frontmatter. plugin.repo.name is unset in the
// Maven prod/gemini profiles; Target falls back repoName->name (="shipsmooth"),
// so passing pluginRepoName="shipsmooth" matches. (windows sets it explicitly,
// also to shipsmooth.)
// ---------------------------------------------------------------------------
val prodDescription = "Agent coding workflow with plan-before-implement discipline, " +
    "TDD, vertical slices, Linear integration, and immutable git-based plan versioning."

val claudeProdSpec = claudeDevSpec.copy(
    // buildEnv=prod now AUTOMATICALLY derives experimentalEnabled=false (RenderSpec
    // derives it from buildEnv) — no separate field to keep in sync.
    buildEnv = BuildEnv.PROD,
    pluginDescription = prodDescription,
    skillFrontmatter = "",
    jlinkDir = constJlink("/dev/null"),
    outputDir = layout.buildDirectory.dir("render/claude-prod").get().asFile.path,
    // plan-76: prod bootstraps without Node. The hook runs the static POSIX installer
    // (Os.Posix.hookCommand copies it next to hooks.json), passing the cache-subdir name
    // and version as args. Dev keeps the node command (it needs the TS local-jlink branch).
    pluginHookCommand =
        "sh \"\${CLAUDE_PLUGIN_ROOT}/hooks/install-shipsmooth.sh\" shipsmooth $pluginVersion",
)

val geminiProdSpec = claudeProdSpec.copy(
    buildPlatform = "gemini",
    skillFrontmatter = """
        ---
        name: start
        description: Use when starting any task — applies the shipsmooth agent coding workflow.
        ---
    """.trimIndent(),
    // The Maven gemini profile sets no shipsmooth.jlink.dir, so Target defaults it
    // to "" and omits the jlinkDir line. Inheriting claudeProd's "/dev/null" would
    // diverge — parity diff caught this. Keep it empty.
    jlinkDir = constJlink(""),
    outputDir = layout.buildDirectory.dir("render/gemini-prod").get().asFile.path,
    pluginHookCommand =
        "sh \"\${extensionPath}/hooks/install-shipsmooth.sh\" shipsmooth $pluginVersion",
)

// buildOs="windows" so Target renders the Windows cliBin
// (%LOCALAPPDATA%\...\runtime\bin\shipsmooth.cmd) and the install-runtime.bat hook.
// The Maven render now forwards build.os too (Task 20 fixed both systems), so the
// two outputs stay byte-identical. pluginHookCommand is unset because the Windows
// hook is generated by Os.Windows.hookCommand (cmd.exe install-runtime.bat), not
// passed in; the windows Maven profile likewise sets no plugin.hook.base.
val windowsSpec = claudeProdSpec.copy(
    buildOs = "windows",
    pluginDescription = "Agent coding workflow (Windows)",
    // Windows is a cross-build target, NOT host-derived: pin to the windows-x64
    // image regardless of build host. (Path corrected to the Gradle cli output
    // dir; the old cli/target/... was a plan-71 Maven-migration leftover.)
    jlinkDir = constJlink(repoRoot.dir("cli/build/jlink-image-windows-x64").asFile.path),
    pluginHookCommand = "",
    outputDir = layout.buildDirectory.dir("render/windows").get().asFile.path,
)

val renderClaudeProd = registerRender("renderClaudeProd", claudeProdSpec)
val renderGeminiProd = registerRender("renderGeminiProd", geminiProdSpec)
val renderWindows = registerRender("renderWindows", windowsSpec)

// ---------------------------------------------------------------------------
// Payload JS/TS copies (moved here from the packaging module in Task 21 — these
// populate the plugin payload, not the jlink runtime, so they belong with the
// rest of payload assembly; packaging keeps only PackageRuntime/Validate/Publish).
// Source trees are local to this module (scripts/dist, scripts/tasks).
// ---------------------------------------------------------------------------
// Where the assembled payload goes (shared with the render + claude manifests).
val payloadDir = (findProperty("build.outputDir") as String?)
    ?.let { file(it) }
    ?: repoRoot.dir("build").asFile

// Factory: copy the compiled non-test JS that compileTs emits into <distRoot>/dist/.
// Two callers, differing only in destination and overlap-check needs:
//   - dev  (copyDist): co-deposits into the -Pbuild.outputDir payload tree, so it
//     declares its EXACT dest files (not the dist/ dir) — that lets the overlap-check
//     see copyDist owning only the JS, leaving dist/session-start-config.json to the
//     render. (withFileGranularOutputs = true)
//   - prod (copyDistProd): writes a FIXED private staging dir, independent of
//     -Pbuild.outputDir; the prod assemble Sync (sole writer of the final dir) merges
//     it, so no overlap-check and no file-granular declaration is needed.
// (plan-71 Task 21 dev / Task 23 prod dual-mode.)
fun registerCopyDist(taskName: String, distRoot: File, withFileGranularOutputs: Boolean) =
    tasks.register<Copy>(taskName) {
        group = "assemble"
        description = "Copy compiled JS (minus *.test.js) into $distRoot/dist."
        dependsOn(compileTs)
        val src = layout.projectDirectory.dir("scripts/dist")
        from(src) { exclude("**/*.test.js") }
        val dest = File(distRoot, "dist")
        into(dest)
        if (withFileGranularOutputs) {
            val jsFiles = fileTree(src) { include("**/*.js"); exclude("**/*.test.js") }
                .files.map { File(dest, it.name) }
            outputs.files(jsFiles)
        }
    }

val copyDist = registerCopyDist("copyDist", payloadDir, withFileGranularOutputs = true)
val copyDistProd = registerCopyDist(
    "copyDistProd",
    layout.buildDirectory.dir("stage/dist-prod").get().asFile,
    withFileGranularOutputs = false,
)

// NOTE (Task 23): no variant's payload contains a scripts/tasks/ tree — verified
// against fresh Maven prod AND windows baselines (both emit only skills/, hooks/,
// dist/, .claude-plugin/, and the gemini/windows extras). The earlier copyScripts /
// copyTsSource tasks here were speculative ("Used by Tasks 23/25") and had no real
// consumer, so they were removed. If Task 25 (Windows) turns out to need a runtime
// TS-source copy, re-introduce it then against an actual Windows baseline.

// The assembleClaude*/assembleGemini* entry points live in the claude/ and gemini/
// integration build scripts (plan-71 v15, isolation by audience), each calling the
// shared buildSrc registerPayloadAssembly() (dev) / registerPayloadSync() (prod)
// helper with its own producers. skills:pkg exposes only the shared producers above
// (render*, copyDist, copyDistProd).





