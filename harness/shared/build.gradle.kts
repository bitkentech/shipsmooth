import com.github.gradle.node.npm.task.NpmInstallTask
import com.github.gradle.node.npm.task.NpmTask
import org.gradle.api.tasks.PathSensitivity.RELATIVE

plugins {
    id("shipsmooth.java-conventions")
    // node-gradle: content-hashed input/output tracking for the npm/tsc pipeline.
    id("com.github.node-gradle.node") version "7.1.0"
}

// plugin-resources renders "the rest" of the plugin payload (everything that is
// not the SKILL.md itself): the SessionStart hook command + its companion files
// (HookCommandRenderer), hooks.json (HooksRenderer), session-start-config.json
// (SessionStartConfigRenderer), the Node session-start hook (scripts/), the POSIX
// bootstrap installer (install-shipsmooth.sh), and the Target orchestrator that
// ties them together with the skill render. It depends on :plugin-model (Os etc.)
// and :skills:pkg (SkillRenderer + the precompiled JTE template classes Target's
// classpath needs). (plan-79 Tasks 3 + 4.)
dependencies {
    implementation(project(":plugin-model"))
    implementation(project(":skills:pkg"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

node {
    // System Node — do not download a Node distribution.
    download.set(false)
    nodeProjectDir.set(file("scripts"))
}

// `npm install`, keyed on package-lock.json -> node_modules.
tasks.named<NpmInstallTask>("npmInstall") {
    inputs.file("scripts/package-lock.json").withPathSensitivity(RELATIVE)
    outputs.dir("scripts/node_modules")
}

// `npm run build` (tsc + esbuild bundle).
val compileTs by tasks.registering(NpmTask::class) {
    dependsOn(tasks.named<NpmInstallTask>("npmInstall"))
    args.set(listOf("run", "build"))

    inputs.dir("scripts/tasks").withPathSensitivity(RELATIVE)
    inputs.file("scripts/package.json").withPathSensitivity(RELATIVE)
    inputs.file("scripts/tsconfig.json").withPathSensitivity(RELATIVE)
    outputs.dir("scripts/dist")
}

// `npm test` (tsc -p tsconfig.test.json + bundle-test + node --test).
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

tasks.named("check") { dependsOn(testTs) }

// plan-76: lint the static POSIX bootstrap script. `sh -n` (syntax) always runs;
// `shellcheck` runs only when installed, so the build never hard-fails on a
// missing optional linter but enforces it where present.
val installScript = layout.projectDirectory.file("src/main/resources/install-shipsmooth.sh")
val lintInstallScript by tasks.registering(Exec::class) {
    description = "Syntax-check install-shipsmooth.sh (sh -n always; shellcheck if available)."
    group = "verification"
    inputs.file(installScript)
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

// ---------------------------------------------------------------------------
// Render targets (each a JavaExec running io.bitken.ss.resources.Target with a
// full RenderSpec tuple). Modelling the variables as one RenderSpec (in buildSrc)
// keeps the targets from drifting. (Moved here from skills:pkg in plan-79 Task 4 —
// Target lives here now, and its runtime classpath transitively carries the
// precompiled JTE template classes from :skills:pkg.)
// ---------------------------------------------------------------------------

// plugin.version mirrors Maven's @project.version@, sourced from a gradle property.
val pluginVersion = (findProperty("plugin.version") as String?)
    ?: error("plugin.version must be set (gradle.properties) to match the Maven project version")
// harness/shared is nested two levels under the repo root (harness/shared/).
val repoRoot = layout.projectDirectory.dir("../..")

// Dev jlinkDir resolves LAZILY from the cli jlink image for THIS build host.
val cliProject = project(":cli")
evaluationDependsOn(":cli")
val hostTag = HostPlatform.tag()
val devJlinkDir: Provider<String> =
    cliProject.tasks.named("image_$hostTag")
        .map { it.outputs.files.singleFile.path }

fun constJlink(value: String): Provider<String> = provider { value }

// Resolve a render output dir: -Pbuild.outputDir overrides, else the per-variant
// default under the module build dir (back-compat for standalone renderX runs).
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
    outputDir = renderOutputDir("gemini-dev"),
    pluginHookCommand = "node \"\${extensionPath}/dist/session-start.js\"",
)

val codexDevSpec = geminiDevSpec.copy(
    buildPlatform = "codex",
    outputDir = renderOutputDir("codex-dev"),
    pluginHookCommand = "node \"\${PLUGIN_ROOT}/dist/session-start.js\"",
)

// OpenCode (plan-86): no hooks.json (Platform.Opencode.emitsHooksJson()==false),
// so the hook command is NOT consumed by any host file — but it must still
// reference install-shipsmooth.sh so HookCommandRenderer's POSIX branch copies the
// script into hooks/ (the JS plugin shells out to it). OpenCode therefore uses the
// sh-installer command form in BOTH dev and prod (there is no session-start.js Node
// path for opencode). Frontmatter is required (like gemini/codex).
val opencodeDevSpec = geminiDevSpec.copy(
    buildPlatform = "opencode",
    outputDir = renderOutputDir("opencode-dev"),
    pluginHookCommand =
        "sh \"\${PLUGIN_ROOT}/hooks/install-shipsmooth.sh\" shipsmooth-dev $pluginVersion",
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
        jvmArgumentProviders.add {
            props.map { (key, value) -> "-D$key=${value.get()}" }
        }

        props.forEach { (key, value) -> inputs.property(key, value) }
        inputs.files(runtimeClasspath).withNormalizer(ClasspathNormalizer::class.java)
        outputs.dir("${spec.outputDir}/skills")
        outputs.dir("${spec.outputDir}/hooks")
        outputs.file("${spec.outputDir}/dist/session-start-config.json")
    }

val renderClaudeDev = registerRender("renderClaudeDev", claudeDevSpec)
val renderGeminiDev = registerRender("renderGeminiDev", geminiDevSpec)
val renderCodexDev = registerRender("renderCodexDev", codexDevSpec)
val renderOpencodeDev = registerRender("renderOpencodeDev", opencodeDevSpec)

// ---------------------------------------------------------------------------
// Prod render variants. Prod deltas vs dev: buildEnv=prod, experimentalEnabled=false,
// prod description, empty/prod frontmatter.
// ---------------------------------------------------------------------------
val prodDescription = "Agent coding workflow with plan-before-implement discipline, " +
    "TDD, vertical slices, Linear integration, and immutable git-based plan versioning."

val claudeProdSpec = claudeDevSpec.copy(
    buildEnv = BuildEnv.PROD,
    pluginDescription = prodDescription,
    skillFrontmatter = "",
    jlinkDir = constJlink("/dev/null"),
    outputDir = layout.buildDirectory.dir("render/claude-prod").get().asFile.path,
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
    jlinkDir = constJlink(""),
    outputDir = layout.buildDirectory.dir("render/gemini-prod").get().asFile.path,
    pluginHookCommand =
        "sh \"\${extensionPath}/hooks/install-shipsmooth.sh\" shipsmooth $pluginVersion",
)

val codexProdSpec = geminiProdSpec.copy(
    buildPlatform = "codex",
    outputDir = layout.buildDirectory.dir("render/codex-prod").get().asFile.path,
    pluginHookCommand =
        "sh \"\${PLUGIN_ROOT}/hooks/install-shipsmooth.sh\" shipsmooth $pluginVersion",
)

// OpenCode prod: prod frontmatter (name: start) + prod description from geminiProdSpec;
// sh-installer hook command so the script is copied (no hooks.json is written).
val opencodeProdSpec = geminiProdSpec.copy(
    buildPlatform = "opencode",
    outputDir = layout.buildDirectory.dir("render/opencode-prod").get().asFile.path,
    pluginHookCommand =
        "sh \"\${PLUGIN_ROOT}/hooks/install-shipsmooth.sh\" shipsmooth $pluginVersion",
)

val windowsSpec = claudeProdSpec.copy(
    buildOs = "windows",
    pluginDescription = "Agent coding workflow (Windows)",
    jlinkDir = constJlink(repoRoot.dir("cli/build/jlink-image-windows-x64").asFile.path),
    pluginHookCommand = "",
    outputDir = layout.buildDirectory.dir("render/windows").get().asFile.path,
)

val renderClaudeProd = registerRender("renderClaudeProd", claudeProdSpec)
val renderGeminiProd = registerRender("renderGeminiProd", geminiProdSpec)
val renderCodexProd = registerRender("renderCodexProd", codexProdSpec)
val renderOpencodeProd = registerRender("renderOpencodeProd", opencodeProdSpec)
val renderWindows = registerRender("renderWindows", windowsSpec)

// ---------------------------------------------------------------------------
// Payload JS/TS copies — populate the plugin payload (not the jlink runtime).
// Source trees are local to this module (scripts/dist).
// ---------------------------------------------------------------------------
val payloadDir = (findProperty("build.outputDir") as String?)
    ?.let { file(it) }
    ?: repoRoot.dir("build").asFile

// Factory: copy the compiled non-test JS that compileTs emits into <distRoot>/dist/.
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
