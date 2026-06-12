// Codex integration (plan-77): assemble a Codex CLI plugin payload. Codex's plugin
// shape is closest to Claude's (a .codex-plugin/plugin.json manifest + bundled skills/
// + hooks/), but with TWO structural differences the de-risk pinned down:
//   1. The plugin lives UNDER  <root>/plugins/<name>/  (not at the payload root).
//   2. The marketplace registration is  <root>/.agents/plugins/marketplace.json
//      (a separate file one level up, NOT inside the plugin; NOT a loose root file).
// So this module does not reuse the flat registerPayloadAssembly/Sync helpers (which
// merge every producer into the payload root) — the nesting is expressed directly with
// Copy (dev) / Sync (prod) tasks here. Token-filtering of the two manifests mirrors the
// claude module's registerClaudeMeta (Copy + expand()).
//
// No java-conventions plugin — resource filtering only (parity with claude/gemini).
plugins {
    base
}

val pluginBaseName = (findProperty("plugin.base.name") as String?) ?: "shipsmooth"
val pluginVersion = (findProperty("plugin.version") as String?) ?: "0.3.19"
val prodDescription = "Agent coding workflow with plan-before-implement discipline, " +
    "TDD, vertical slices, Linear integration, and immutable git-based plan versioning."

// The plugin folder name = the Codex plugin name (kebab-case). Codex requires the
// skill folder name to match its frontmatter `name` (skills/start/), but the PLUGIN
// folder is the plugin's own name.
val pluginName = pluginBaseName            // prod: "shipsmooth"
val pluginNameDev = "$pluginBaseName-dev"  // dev:  "shipsmooth-dev"

// Per-variant tokens for the two filtered manifests.
fun tokens(name: String, description: String, marketplaceName: String, marketplaceDesc: String) = mapOf(
    "plugin" to mapOf("name" to name, "description" to description),
    "project" to mapOf("version" to pluginVersion),
    "marketplace" to mapOf("name" to marketplaceName, "description" to marketplaceDesc),
)

val devTokens = tokens(
    pluginNameDev, "Agent coding workflow (dev build)",
    "shipsmooth-dev", "Development marketplace for bitkentech/shipsmooth (Codex)",
)
val prodTokens = tokens(
    pluginName, prodDescription,
    "bitkentech", "Plugin marketplace for bitkentech/shipsmooth (Codex)",
)

// Where the assembled payload (the marketplace ROOT) goes. -Pbuild.outputDir targets
// the shared payload tree; standalone defaults to the repo build-codex-dev/ dir (NOT
// build-codex/, which holds the hand-built Task-1 de-risk artifact). Both are gitignored.
val outputDir = (findProperty("build.outputDir") as String?)
    ?.let { file(it) }
    ?: rootProject.layout.projectDirectory.dir("build-codex-dev").asFile

val pluginResources = layout.projectDirectory.dir("src/main/resources/codex-plugin")
val marketplaceResources = layout.projectDirectory.dir("src/main/resources/codex-marketplace")

// Factory: filter the plugin manifest (.codex-plugin/plugin.json) into
// <baseDir>/plugins/<name>/.codex-plugin/ AND the marketplace registration
// (.agents/plugins/marketplace.json) into <baseDir>/.agents/plugins/. Declares exact
// output files so the overlap-check can attribute them. Returns the Copy task provider.
fun registerCodexMeta(taskName: String, tokens: Map<String, Any>, pluginFolder: String, baseDir: File) =
    tasks.register<Copy>(taskName) {
        group = "assemble"
        description = "Filter the Codex plugin.json + marketplace.json into <baseDir>."
        // .codex-plugin/plugin.json → plugins/<name>/.codex-plugin/plugin.json
        from(pluginResources) { into("plugins/$pluginFolder") }
        // .agents/plugins/marketplace.json → <root>/.agents/plugins/marketplace.json
        from(marketplaceResources)
        into(baseDir)
        expand(tokens)
        // expand() tokens aren't auto-tracked; declare them so a version/name bump re-renders.
        inputs.property("tokens", tokens)
        outputs.file(File(baseDir, "plugins/$pluginFolder/.codex-plugin/plugin.json"))
        outputs.file(File(baseDir, ".agents/plugins/marketplace.json"))
    }

// codex references :skills:pkg's producer tasks, so evaluate it first.
evaluationDependsOn(":skills:pkg")
val skillsPkg = project(":skills:pkg")

// Both variants assemble via a Sync from PRIVATE staging dirs (not the flat dev
// co-deposit the claude/gemini dev path uses). Codex's nested layout — render + dist
// under plugins/<name>/, manifests one level up — cannot be expressed by the flat
// co-deposit (where render/copyDist write straight into the payload root), so codex
// uses the staging+Sync model for dev too. The render must therefore write to its
// own default render dir (build/render/codex-{dev,prod}), i.e. assembleCodex* must NOT
// be invoked with -Pbuild.outputDir overriding the render target.
//
// The compiled JS (session-start.js) is identical across dev/prod (same TS build), so
// both variants source it from copyDistProd's private staging dir; copyDistProd never
// touches the payload root, so it is overlap-safe to reuse here.
val codexDistStage = skillsPkg.layout.buildDirectory.dir("stage/dist-prod").get().asFile

// ---------------------------------------------------------------------------
// assembleCodexDev → build-codex-dev/. Sync is the sole writer of the payload (prunes
// stale files, overlap-immune). Mirrors the codex-prod path but with dev tokens and
// the dev render (which carries the start-dev frontmatter + experimental skills).
// ---------------------------------------------------------------------------
val codexDevRenderStage = skillsPkg.layout.buildDirectory.dir("render/codex-dev").get().asFile
val codexDevMetaStage = layout.buildDirectory.dir("stage/codex-dev-meta").get().asFile
val copyCodexMetaDev = registerCodexMeta("copyCodexMetaDev", devTokens, pluginNameDev, codexDevMetaStage)

tasks.register<Sync>("assembleCodexDev") {
    group = "assemble"
    description = "Assemble the full codex-dev plugin payload into build-codex-dev/."
    dependsOn(skillsPkg.tasks.named("renderCodexDev"), skillsPkg.tasks.named("copyDistProd"), copyCodexMetaDev)
    from(codexDevRenderStage) { into("plugins/$pluginNameDev") }
    from(codexDistStage) { into("plugins/$pluginNameDev") }
    from(codexDevMetaStage) // carries plugins/<name>/.codex-plugin + .agents/plugins layout
    into(outputDir)
}

// ---------------------------------------------------------------------------
// assembleCodexProd → <build.outputDir> (pass -Pbuild.outputDir). Sync is the sole
// writer (overlap-immune; mirrors assembleClaudeProd's dual-mode prod path).
// ---------------------------------------------------------------------------
val codexProdRenderStage = skillsPkg.layout.buildDirectory.dir("render/codex-prod").get().asFile
val codexProdMetaStage = layout.buildDirectory.dir("stage/codex-prod-meta").get().asFile
val copyCodexMetaProd = registerCodexMeta("copyCodexMetaProd", prodTokens, pluginName, codexProdMetaStage)

tasks.register<Sync>("assembleCodexProd") {
    group = "assemble"
    description = "Assemble the full codex-prod plugin payload into <build.outputDir> (pass -Pbuild.outputDir)."
    dependsOn(skillsPkg.tasks.named("renderCodexProd"), skillsPkg.tasks.named("copyDistProd"), copyCodexMetaProd)
    from(codexProdRenderStage) { into("plugins/$pluginName") }
    from(codexDistStage) { into("plugins/$pluginName") }
    from(codexProdMetaStage) // carries plugins/<name>/.codex-plugin + .agents/plugins layout
    into(outputDir)
}
