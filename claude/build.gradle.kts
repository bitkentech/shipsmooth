// Claude integration: filter the .claude-plugin manifests (plugin.json,
// marketplace.json) into build/.claude-plugin/, replacing the Maven
// maven-resources-plugin copy-resources filtering with Copy + expand().
//
// No java-conventions plugin here — this module has no Java, only resource
// filtering (parity with claude/pom.xml, which is build-only).

val pluginBaseName = (findProperty("plugin.base.name") as String?) ?: "shipsmooth"
val pluginVersion = (findProperty("plugin.version") as String?) ?: "0.3.14"
val prodDescription = "Agent coding workflow with plan-before-implement discipline, " +
    "TDD, vertical slices, Linear integration, and immutable git-based plan versioning."

// Per-variant token tuples. No global -Pvariant / default — each variant has its own
// explicit manifest task (see registerClaudeMeta below), so the dev and prod payloads
// can both be assembled correctly in a single build. (plan-71 no-default-variants.)
val devTokens = mapOf(
    "plugin" to mapOf("name" to "$pluginBaseName-dev", "description" to "Agent coding workflow (dev build)"),
    "project" to mapOf("version" to pluginVersion),
    "marketplace" to mapOf(
        "name" to "shipsmooth-dev",
        "description" to "Development marketplace for bitkentech/shipsmooth plugin",
    ),
)
val prodTokens = mapOf(
    "plugin" to mapOf("name" to pluginBaseName, "description" to prodDescription),
    "project" to mapOf("version" to pluginVersion),
    "marketplace" to mapOf(
        "name" to "bitkentech",
        "description" to "Plugin marketplace for bitkentech/shipsmooth",
    ),
)

// Where the manifests land. -Pbuild.outputDir targets the shared payload tree (so an
// assembleX task can drive it); defaults to the repo build/ dir for standalone runs.
val outputDir = (findProperty("build.outputDir") as String?)
    ?.let { file(it) }
    ?: rootProject.layout.projectDirectory.dir("build").asFile

// Factory: one .claude-plugin manifest task per variant (plugin.json + marketplace.json,
// token-filtered). Mirrors skills/pkg's registerRender(spec) pattern. Declares its EXACT
// output files (not just the dest dir) so the payload overlap-check can attribute them
// (Task 21, Bazel-style); the manifest set is a fixed two files.
fun registerClaudeMeta(taskName: String, tokens: Map<String, Any>) =
    tasks.register<Copy>(taskName) {
        group = "assemble"
        description = "Filter plugin.json + marketplace.json into <build.outputDir>/.claude-plugin/."
        from(layout.projectDirectory.dir("src/main/resources/claude-plugin"))
        val dest = File(outputDir, ".claude-plugin")
        into(dest)
        expand(tokens)
        outputs.file(File(dest, "plugin.json"))
        outputs.file(File(dest, "marketplace.json"))
    }

val copyClaudeMetaDev = registerClaudeMeta("copyClaudeMetaDev", devTokens)
val copyClaudeMetaProd = registerClaudeMeta("copyClaudeMetaProd", prodTokens)

// ---------------------------------------------------------------------------
// assembleClaudeDev (Task 21): the full claude-dev plugin payload into one dir —
// skills:pkg's render (skills/ + hooks/ + dist/session-start-config.json) + its
// dist JS (copyDist), plus this module's .claude-plugin manifests. No scripts/tasks
// (the dev payload has none). Co-deposit + overlap-check via the shared buildSrc
// helper; this entry point lives here (not skills:pkg / not gemini) so the Claude
// build is self-contained. Does NOT invoke packaging. (plan-71 v15.)
// Run with -Pbuild.outputDir=<dir> to target a specific tree (e.g. build/).
// ---------------------------------------------------------------------------
// claude references :skills:pkg's producer tasks (renderClaudeDev, copyDist), so its
// build script must be evaluated first — otherwise those tasks aren't registered yet.
evaluationDependsOn(":skills:pkg")
val skillsPkg = project(":skills:pkg")
registerPayloadAssembly(
    assembleTaskName = "assembleClaudeDev",
    description = "Assemble the full claude-dev plugin payload into <build.outputDir> (default build/).",
    payloadDir = outputDir,
    producers = listOf(
        PayloadProducer("renderClaudeDev", skillsPkg.tasks.named("renderClaudeDev"), ownsFilesOnly = false),
        PayloadProducer("copyDist", skillsPkg.tasks.named("copyDist"), ownsFilesOnly = true),
        PayloadProducer("copyClaudeMetaDev", copyClaudeMetaDev, ownsFilesOnly = true),
    ),
)

