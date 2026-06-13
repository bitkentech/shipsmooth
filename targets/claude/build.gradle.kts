// Claude integration: filter the .claude-plugin manifests (plugin.json,
// marketplace.json) into build/.claude-plugin/, replacing the Maven
// maven-resources-plugin copy-resources filtering with Copy + expand().
//
// No java-conventions plugin here — this module has no Java, only resource
// filtering (parity with claude/pom.xml, which is build-only). The `base` plugin
// gives us `clean` + the lifecycle tasks (assembleX already hook in) without any
// Java toolchain, so the root `./gradlew clean` reaches this module's build/.
plugins {
    base
}

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
// Windows variant (Maven `windows` profile): prod plugin.name, Windows-specific plugin
// description, and a -windows marketplace description. (plan-71 Task 25.)
val windowsTokens = mapOf(
    "plugin" to mapOf("name" to pluginBaseName, "description" to "Agent coding workflow (Windows)"),
    "project" to mapOf("version" to pluginVersion),
    "marketplace" to mapOf(
        "name" to "bitkentech",
        "description" to "Plugin marketplace for bitkentech/shipsmooth-windows",
    ),
)

// Where the manifests land. -Pbuild.outputDir targets the shared payload tree (so an
// assembleX task can drive it); defaults to the repo build/ dir for standalone runs.
val outputDir = (findProperty("build.outputDir") as String?)
    ?.let { file(it) }
    ?: rootProject.layout.projectDirectory.dir("build").asFile

// Factory: one .claude-plugin manifest task per variant (plugin.json + marketplace.json,
// token-filtered) writing into <baseDir>/.claude-plugin/. Mirrors skills/pkg's
// registerRender(spec) pattern. Declares its EXACT output files (not just the dest dir)
// so the payload overlap-check can attribute them (Task 21, Bazel-style); the manifest
// set is a fixed two files.
//   - dev  baseDir = outputDir (the -Pbuild.outputDir co-deposit tree)
//   - prod baseDir = a FIXED private staging dir, merged later by the assemble Sync
fun registerClaudeMeta(taskName: String, tokens: Map<String, Any>, baseDir: File) =
    tasks.register<Copy>(taskName) {
        group = "assemble"
        description = "Filter plugin.json + marketplace.json into <baseDir>/.claude-plugin/."
        from(layout.projectDirectory.dir("src/main/resources/claude-plugin"))
        val dest = File(baseDir, ".claude-plugin")
        into(dest)
        expand(tokens)
        // expand() values are NOT auto-tracked as inputs, so a version (or any token)
        // bump would leave this task UP-TO-DATE and re-stamp nothing. Declare the tokens
        // as an input property so a changed plugin.version re-renders the manifest.
        inputs.property("tokens", tokens)
        outputs.file(File(dest, "plugin.json"))
        outputs.file(File(dest, "marketplace.json"))
    }

// Fixed private staging dirs (independent of -Pbuild.outputDir) for the prod/windows
// Sync paths.
val claudeProdMetaStage = layout.buildDirectory.dir("stage/claude-prod-meta").get().asFile
val windowsMetaStage = layout.buildDirectory.dir("stage/windows-meta").get().asFile

val copyClaudeMetaDev = registerClaudeMeta("copyClaudeMetaDev", devTokens, outputDir)
val copyClaudeMetaProd = registerClaudeMeta("copyClaudeMetaProd", prodTokens, claudeProdMetaStage)
val copyClaudeMetaWindows = registerClaudeMeta("copyClaudeMetaWindows", windowsTokens, windowsMetaStage)

// copyWindowsReadme: the Windows-only README.md (from claude/src/main/resources/windows/,
// unfiltered) into a fixed private staging dir, merged by the assembleWindows Sync.
// Mirrors claude/pom.xml's `copy-windows-readme` execution. (plan-71 Task 25.)
val windowsReadmeStage = layout.buildDirectory.dir("stage/windows-readme").get().asFile
val copyWindowsReadme by tasks.registering(Copy::class) {
    group = "assemble"
    description = "Copy the Windows README.md into a private windows staging dir."
    from(layout.projectDirectory.dir("src/main/resources/windows")) { include("README.md") }
    into(windowsReadmeStage)
    outputs.file(File(windowsReadmeStage, "README.md"))
}

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
evaluationDependsOn(":targets:shared")
val pluginResources = project(":targets:shared")
registerPayloadAssembly(
    assembleTaskName = "assembleClaudeDev",
    description = "Assemble the full claude-dev plugin payload into <build.outputDir> (default build/).",
    payloadDir = outputDir,
    producers = listOf(
        PayloadProducer("renderClaudeDev", pluginResources.tasks.named("renderClaudeDev"), ownsFilesOnly = false),
        PayloadProducer("copyDist", pluginResources.tasks.named("copyDist"), ownsFilesOnly = true),
        PayloadProducer("copyClaudeMetaDev", copyClaudeMetaDev, ownsFilesOnly = true),
    ),
)

// ---------------------------------------------------------------------------
// devBuild: local dev-loop convenience. assembleClaudeDev's producers resolve
// their output dir from the project-wide `build.outputDir` property at configuration
// time (renderOutputDir in skills:pkg), so a no-arg `assembleClaudeDev` would
// co-deposit only the manifests into build/ and render the skills into
// skills/pkg/build/render — a confusing split. Rather than retarget the carefully
// module-local render defaults, this spawns a nested build with build.outputDir=build
// set, so the FULL claude-dev payload (skills/ + hooks/ + dist/ + .claude-plugin/)
// lands in repo-root build/ — the dir Claude reads the dev plugin from
// (docs/proposals/build-migrate.md §3).
//
// The host jlink image is built automatically: assembleClaudeDev -> renderClaudeDev,
// whose dev jlinkDir provider is the :cli:image_<host> task output (plan-74
// Task 5). No manual dependsOn and no -PjlinkBuild flag — the dependency edge does it.
// Run: ./gradlew :targets:claude:devBuild  (then point Claude / shipsmooth-dev at build/).
val devBuild by tasks.registering(GradleBuild::class) {
    group = "assemble"
    description = "Assemble the full claude-dev payload into repo-root build/ for local dev/test."
    dir = rootProject.projectDir
    tasks = listOf(":targets:claude:assembleClaudeDev")
    startParameter.projectProperties = startParameter.projectProperties +
        mapOf("build.outputDir" to rootProject.layout.projectDirectory.dir("build").asFile.absolutePath)
}

// ---------------------------------------------------------------------------
// assembleClaudeProd (Task 23): the full claude-prod plugin payload, via the prod
// dual-mode Sync path. Each producer writes into its OWN private staging dir —
// renderClaudeProd (skills/ + hooks/ + dist/session-start-config.json) into
// skills:pkg's build/render/claude-prod, copyDistProd (dist/*.js) into its prod
// staging, copyClaudeMetaProd (.claude-plugin manifests, prod tokens) into
// claudeProdMetaStage — and assembleClaudeProd Syncs all three into <build.outputDir>
// as the SOLE writer (overlap-immune; no overlap-check on the release path).
// (plan-71 v11-v15 dual-mode.) Note: the claude-prod payload has NO scripts/tasks
// tree (the Maven prod baseline emits none) — just skills/, hooks/, dist/, .claude-plugin/.
// ---------------------------------------------------------------------------
val claudeProdRenderStage = pluginResources.layout.buildDirectory.dir("render/claude-prod").get().asFile
val claudeProdDistStage = pluginResources.layout.buildDirectory.dir("stage/dist-prod").get().asFile
registerPayloadSync(
    syncTaskName = "assembleClaudeProd",
    description = "Assemble the full claude-prod plugin payload into <build.outputDir> (default build/).",
    payloadDir = outputDir,
    sources = listOf(
        SyncSource(pluginResources.tasks.named("renderClaudeProd"), claudeProdRenderStage),
        SyncSource(pluginResources.tasks.named("copyDistProd"), claudeProdDistStage),
        SyncSource(copyClaudeMetaProd, claudeProdMetaStage),
    ),
)

// ---------------------------------------------------------------------------
// assembleWindows (Task 25): the full windows plugin payload, via the prod Sync path.
// Windows is a Claude plugin, so it carries .claude-plugin/ (windows tokens), but it
// DIFFERS from claude-prod: skip.copy-dist=true, so there is NO copyDist(Prod) — the
// payload has no dist/*.js, only the render's dist/session-start-config.json. The
// Windows-specific hooks/ (cmd.exe + install-runtime.bat, Task 20) come from
// renderWindows. A Windows-only README.md (copyWindowsReadme) is included.
// Producers: renderWindows + copyClaudeMetaWindows + copyWindowsReadme, each in its
// own private staging dir; assembleWindows Syncs them into <build.outputDir> as the
// SOLE writer. (plan-71 v11-v15 dual-mode.)
// ---------------------------------------------------------------------------
val windowsRenderStage = pluginResources.layout.buildDirectory.dir("render/windows").get().asFile
registerPayloadSync(
    syncTaskName = "assembleWindows",
    description = "Assemble the full windows plugin payload into <build.outputDir>.",
    payloadDir = outputDir,
    sources = listOf(
        SyncSource(pluginResources.tasks.named("renderWindows"), windowsRenderStage),
        SyncSource(copyClaudeMetaWindows, windowsMetaStage),
        SyncSource(copyWindowsReadme, windowsReadmeStage),
    ),
)

