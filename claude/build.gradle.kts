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
// token-filtered). Mirrors skills/pkg's registerRender(spec) pattern.
fun registerClaudeMeta(taskName: String, tokens: Map<String, Any>) =
    tasks.register<Copy>(taskName) {
        group = "assemble"
        description = "Filter plugin.json + marketplace.json into <build.outputDir>/.claude-plugin/."
        from(layout.projectDirectory.dir("src/main/resources/claude-plugin"))
        into(File(outputDir, ".claude-plugin"))
        expand(tokens)
    }

val copyClaudeMetaDev = registerClaudeMeta("copyClaudeMetaDev", devTokens)
val copyClaudeMetaProd = registerClaudeMeta("copyClaudeMetaProd", prodTokens)
