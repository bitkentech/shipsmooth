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
// This build's root project IS skills/pkg, so the repo root is two levels up
// (skills/pkg -> skills -> repo root), not one.
val repoRoot = layout.projectDirectory.dir("../..")
val jlinkDir = repoRoot.dir("cli/target/jlink-image").asFile.path

val claudeDevSpec = RenderSpec(
    buildPlatform = "claude",
    buildOs = "posix",
    buildEnv = "dev",
    pluginBaseName = "shipsmooth",
    pluginVersion = pluginVersion,
    pluginDescription = "Agent coding workflow (dev build)",
    pluginSkillStartBasename = "start",
    skillFrontmatter = "",
    jlinkDir = jlinkDir,
    pluginRepoName = "shipsmooth",
    outputDir = layout.buildDirectory.dir("render/claude-dev").get().asFile.path,
    experimentalEnabled = true,
    pluginHookCommand = "node \"\${CLAUDE_PLUGIN_ROOT}/dist/session-start.js\"",
)

val geminiDevSpec = claudeDevSpec.copy(
    buildPlatform = "gemini",
    skillFrontmatter = """
        ---
        name: start-dev
        description: Use when starting any task — applies the shipsmooth agent coding workflow (dev build).
        ---
    """.trimIndent(),
    outputDir = layout.buildDirectory.dir("render/gemini-dev").get().asFile.path,
    pluginHookCommand = "node \"\${extensionPath}/dist/session-start.js\"",
)

fun registerRender(taskName: String, spec: RenderSpec) =
    tasks.register<JavaExec>(taskName) {
        group = "render"
        description = "Render the ${spec.buildPlatform}-${spec.buildEnv} plugin variant via Target."
        dependsOn(tasks.named("compileJava"), compileTs)

        val runtimeClasspath = sourceSets["main"].runtimeClasspath
        classpath = runtimeClasspath
        mainClass.set("io.bitken.ss.resources.Target")
        systemProperties(spec.systemProperties())

        // Inputs: the render is a pure function of (a) the RenderSpec tuple and
        // (b) the runtime classpath — which carries the compiled JTE template
        // classes, so a .jte.md edit (-> stageJte -> generateJte -> compileJava)
        // busts this task. With these declared, an unchanged render is
        // UP-TO-DATE instead of re-running every invocation.
        spec.systemProperties().forEach { (key, value) -> inputs.property(key, value) }
        inputs.files(runtimeClasspath).withNormalizer(ClasspathNormalizer::class.java)
        outputs.dir(spec.outputDir)
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
    buildEnv = "prod",
    pluginDescription = prodDescription,
    skillFrontmatter = "",
    jlinkDir = "/dev/null",
    outputDir = layout.buildDirectory.dir("render/claude-prod").get().asFile.path,
    experimentalEnabled = false,
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
    jlinkDir = "",
    outputDir = layout.buildDirectory.dir("render/gemini-prod").get().asFile.path,
    pluginHookCommand = "node \"\${extensionPath}/dist/session-start.js\"",
)

// The Maven render exec (skills/pkg render-plugin-resources) does NOT pass
// build.os — Target always reads it as the "posix" default. The windows profile's
// build.os=windows only affects downstream packaging (PackageRuntime injects the
// Windows .cmd/.bat content), not this render. So buildOs stays "posix" here;
// setting it to "windows" would render Windows-specific SKILL/hooks that the Maven
// build-windows/ payload does not contain (parity diff caught this). Only the
// windows-specific jlink dir is passed, mirroring the profile's shipsmooth.jlink.dir.
val windowsSpec = claudeProdSpec.copy(
    pluginDescription = "Agent coding workflow (Windows)",
    jlinkDir = repoRoot.dir("cli/target/jlink-image-windows-x64").asFile.path,
    // The Maven windows profile leaves plugin.hook.base unset, so the render's
    // plugin.hook.command resolves to "" and Posix.hookCommand returns empty (no
    // session-start hook, no install-runtime.bat — that wiring is added later by
    // packaging). Inheriting claudeProd's node command diverges — diff caught it.
    pluginHookCommand = "",
    outputDir = layout.buildDirectory.dir("render/windows").get().asFile.path,
)

val renderClaudeProd = registerRender("renderClaudeProd", claudeProdSpec)
val renderGeminiProd = registerRender("renderGeminiProd", geminiProdSpec)
val renderWindows = registerRender("renderWindows", windowsSpec)
