// Gemini integration: filter gemini-extension.json into the payload tree and copy
// the variant's commands/ tree + README, replacing the Maven maven-resources-plugin
// filtering with Copy + expand(). No Java in this module (parity with gemini/pom.xml).
//
// No global -Pvariant default (plan-71 no-default-variants): each variant has its own
// explicit manifest/commands tasks via the registerGeminiMeta factory, so dev and prod
// payloads can both be assembled correctly in one build. assembleGeminiDev / GeminiProd
// live here (isolation by audience, plan-71 v15), each self-contained and naming only
// its own producers — never referencing the claude integration. Reusable machinery
// (VerifyNoOverlappingOutputs + registerPayloadAssembly) lives in buildSrc.
//
// Note: the gemini payload deliberately does NOT carry .claude-plugin/{plugin,marketplace}.json
// — that is Claude-marketplace metadata and has no place in a Gemini extension (the Maven
// gemini profiles never even set marketplace.name/description, so it shipped half-filtered).
// (plan-71 Task 22; Maven gemini/gemini-dev now skip.copy-plugin-meta=true.)
//
// No java-conventions plugin here — resource filtering only (parity with gemini/pom.xml).
// The `base` plugin gives us `clean` + the lifecycle tasks (assembleX already hook in)
// without any Java toolchain, so the root `./gradlew clean` reaches this module's build/.
plugins {
    base
}

val pluginBaseName = (findProperty("plugin.base.name") as String?) ?: "shipsmooth"
val pluginVersion = (findProperty("plugin.version") as String?) ?: "0.3.14"
val prodDescription = "Agent coding workflow with plan-before-implement discipline, " +
    "TDD, vertical slices, and immutable git-based plan versioning."

// Per-variant token tuples for gemini-extension.json (the only filtered file).
val devTokens = mapOf(
    "plugin" to mapOf("name" to "$pluginBaseName-dev", "description" to "Agent coding workflow (dev build)"),
    "project" to mapOf("version" to pluginVersion),
)
val prodTokens = mapOf(
    "plugin" to mapOf("name" to pluginBaseName, "description" to prodDescription),
    "project" to mapOf("version" to pluginVersion),
)

// Where the gemini metadata lands. -Pbuild.outputDir targets the shared payload tree
// (so an assembleX task can drive it); defaults to the repo build-gemini-dev/ dir for
// standalone runs (matches the Maven gemini-dev build.outputDir default).
val outputDir = (findProperty("build.outputDir") as String?)
    ?.let { file(it) }
    ?: rootProject.layout.projectDirectory.dir("build-gemini-dev").asFile

val resourcesDir = layout.projectDirectory.dir("src/main/resources/gemini-extension")

// Factory: one gemini metadata task PER VARIANT — filters gemini-extension.json,
// copies the variant's commands/ tree + README into <baseDir>. Declares its EXACT
// output files (not just dest dirs) so the payload overlap-check can attribute them
// (Task 21/22, Bazel-style). Returns the Copy task provider.
//   - dev  uses gemini-dev/commands (start-dev.toml); baseDir = the co-deposit outputDir
//   - prod uses gemini/commands     (start.toml);     baseDir = a FIXED private staging
//     dir, merged later by the assemble Sync (mirrors claude's registerClaudeMeta).
fun registerGeminiMeta(taskName: String, tokens: Map<String, Any>, commandsVariant: String, baseDir: File) =
    tasks.register<Copy>(taskName) {
        group = "assemble"
        description = "Filter gemini-extension.json + copy commands/ + README into <baseDir>."
        // 1. Filtered manifest at the payload root.
        from(resourcesDir) { include("gemini-extension.json"); expand(tokens) }
        // 2. README (unfiltered) at the payload root.
        from(resourcesDir) { include("README.md") }
        // 3. The variant's commands/ tree (unfiltered) into commands/.
        from(resourcesDir.dir("$commandsVariant/commands")) { into("commands") }
        into(baseDir)
        // expand() values aren't auto-tracked as inputs, so a version (or any token) bump
        // would leave this UP-TO-DATE and re-stamp nothing. Declare the tokens as an input
        // so a changed plugin.version re-renders gemini-extension.json.
        inputs.property("tokens", tokens)

        // Exact declared outputs for the overlap-check. The commands set is variant-fixed
        // (one .toml today); enumerate the source tree so adding a command is picked up.
        outputs.file(File(baseDir, "gemini-extension.json"))
        outputs.file(File(baseDir, "README.md"))
        fileTree(resourcesDir.dir("$commandsVariant/commands")).files.forEach { cmd ->
            outputs.file(File(File(baseDir, "commands"), cmd.name))
        }
    }

// Fixed private prod staging dir (independent of -Pbuild.outputDir) for the prod Sync path.
val geminiProdMetaStage = layout.buildDirectory.dir("stage/gemini-prod-meta").get().asFile

val copyGeminiMetaDev = registerGeminiMeta("copyGeminiMetaDev", devTokens, "gemini-dev", outputDir)
val copyGeminiMetaProd = registerGeminiMeta("copyGeminiMetaProd", prodTokens, "gemini", geminiProdMetaStage)

// ---------------------------------------------------------------------------
// assembleGeminiDev (Task 22): the full gemini-dev extension payload into one dir —
// skills:pkg's render (skills/ + hooks/ + dist/session-start-config.json) + its dist
// JS (copyDist), plus this module's gemini-extension.json + commands/ + README. The
// gemini-dev Maven profile sets skip.copy-dist=false, so dist/ JS is included. Co-deposit
// + overlap-check via the shared buildSrc helper; this entry point lives here (not
// skills:pkg / not claude) so the Gemini build is self-contained. Does NOT invoke
// packaging. (plan-71 v15.)
// Run with -Pbuild.outputDir=<dir> to target a specific tree.
// ---------------------------------------------------------------------------
// gemini references :skills:pkg's producer tasks (renderGeminiDev, copyDist), so its
// build script must be evaluated first — otherwise those tasks aren't registered yet.
evaluationDependsOn(":harness:shared")
val pluginResources = project(":harness:shared")
registerPayloadAssembly(
    assembleTaskName = "assembleGeminiDev",
    description = "Assemble the full gemini-dev extension payload into <build.outputDir> (default build-gemini-dev/).",
    payloadDir = outputDir,
    producers = listOf(
        PayloadProducer("renderGeminiDev", pluginResources.tasks.named("renderGeminiDev"), ownsFilesOnly = false),
        PayloadProducer("copyDist", pluginResources.tasks.named("copyDist"), ownsFilesOnly = true),
        PayloadProducer("copyGeminiMetaDev", copyGeminiMetaDev, ownsFilesOnly = true),
    ),
)

// ---------------------------------------------------------------------------
// assembleGeminiProd (Task 24): the full gemini-prod extension payload, via the prod
// dual-mode Sync path (mirrors claude's assembleClaudeProd). Each producer writes its
// OWN private staging dir — renderGeminiProd (skills/ + hooks/ + dist/session-start-config.json)
// into skills:pkg's build/render/gemini-prod, copyDistProd (dist/*.js) into its prod
// staging, copyGeminiMetaProd (gemini-extension.json + commands/ + README) into
// geminiProdMetaStage — and assembleGeminiProd Syncs all three into <build.outputDir>
// as the SOLE writer (overlap-immune; no overlap-check on the release path).
// (plan-71 v11-v15 dual-mode.) Note: the gemini payload carries NO .claude-plugin/
// (Task 22) and NO scripts/tasks/ tree (Task 23 — no variant emits one).
// ---------------------------------------------------------------------------
val geminiProdRenderStage = pluginResources.layout.buildDirectory.dir("render/gemini-prod").get().asFile
val geminiProdDistStage = pluginResources.layout.buildDirectory.dir("stage/dist-prod").get().asFile
registerPayloadSync(
    syncTaskName = "assembleGeminiProd",
    description = "Assemble the full gemini-prod extension payload into <build.outputDir> (pass -Pbuild.outputDir).",
    payloadDir = outputDir,
    sources = listOf(
        SyncSource(pluginResources.tasks.named("renderGeminiProd"), geminiProdRenderStage),
        SyncSource(pluginResources.tasks.named("copyDistProd"), geminiProdDistStage),
        SyncSource(copyGeminiMetaProd, geminiProdMetaStage),
    ),
)
