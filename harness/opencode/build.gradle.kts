import com.github.gradle.node.npm.task.NpmInstallTask
import com.github.gradle.node.npm.task.NpmTask

// OpenCode integration (plan-86). Unlike claude/codex/gemini (declarative resource
// bundles), OpenCode's payload includes EXECUTABLE code: a JS plugin compiled from
// the module's own TypeScript (src/main/ts). The payload uses OpenCode's config-dir
// layout — plugin/, skills/, hooks/ — so a developer can point a local OpenCode at
// it with OPENCODE_CONFIG_DIR=<dir> (proven in plan-86 Task 1/5; no --plugin-dir
// flag exists).
//
// Build shape:
//   - node-gradle compileTs (tsc transpile-only, Task 4) -> src/main/ts/dist/index.js
//   - token-filtered package.json (name/description/version)
//   - assembleOpencodeDev/Prod: Sync render (skills/ + hooks/install-shipsmooth.sh +
//     config json, from harness:shared) + the compiled JS + package.json into the
//     payload root. OpenCode emits NO hooks.json (Platform.Opencode, Task 8).
plugins {
    base
    id("com.github.node-gradle.node") version "7.1.0"
}

val pluginBaseName = (findProperty("plugin.base.name") as String?) ?: "shipsmooth"
val pluginVersion = (findProperty("plugin.version") as String?) ?: "0.3.25"
val prodDescription = "Agent coding workflow with plan-before-implement discipline, " +
    "TDD, vertical slices, Linear integration, and immutable git-based plan versioning."

val pluginName = pluginBaseName            // prod: "shipsmooth"
val pluginNameDev = "$pluginBaseName-dev"  // dev:  "shipsmooth-dev"

val tsDir = layout.projectDirectory.dir("src/main/ts")

// System Node (no download), TS project rooted at src/main/ts (mirrors harness:shared).
node {
    download.set(false)
    nodeProjectDir.set(tsDir.asFile)
}

tasks.named<NpmInstallTask>("npmInstall") {
    inputs.file("src/main/ts/package-lock.json").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir("src/main/ts/node_modules")
}

// tsc transpile-only -> src/main/ts/dist/index.js (Task 4: no bundler).
val compileTs by tasks.registering(NpmTask::class) {
    dependsOn(tasks.named<NpmInstallTask>("npmInstall"))
    args.set(listOf("run", "build"))
    inputs.dir("src/main/ts/src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("src/main/ts/package.json").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("src/main/ts/tsconfig.json").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir("src/main/ts/dist")
}

// node:test suite (Task 10). Wired into `check`.
val testTs by tasks.registering(NpmTask::class) {
    dependsOn(tasks.named<NpmInstallTask>("npmInstall"))
    args.set(listOf("test"))
    inputs.dir("src/main/ts/src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/ts/test").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("src/main/ts/tsconfig.test.json").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir("src/main/ts/dist-test")
}
tasks.named("check") { dependsOn(testTs) }

// Token-filtered package.json (the only manifest OpenCode reads for an npm-style
// plugin). expand() uses ${...} placeholders.
fun tokens(name: String, description: String) = mapOf(
    "plugin" to mapOf("name" to name, "description" to description),
    "project" to mapOf("version" to pluginVersion),
)
val devTokens = tokens(pluginNameDev, "Agent coding workflow (dev build)")
val prodTokens = tokens(pluginName, prodDescription)

val pkgResources = tsDir.dir("manifest") // package.json.template lives here

fun registerPackageJson(taskName: String, tokens: Map<String, Any>, baseDir: File) =
    tasks.register<Copy>(taskName) {
        group = "assemble"
        description = "Filter package.json into <baseDir>."
        from(pkgResources)
        into(baseDir)
        expand(tokens)
        inputs.property("tokens", tokens)
        outputs.file(File(baseDir, "package.json"))
    }

// harness:shared owns the render tasks (renderOpencodeDev/Prod) + their output dirs.
evaluationDependsOn(":harness:shared")
val renderModule = project(":harness:shared")

// Where the assembled payload goes. -Pbuild.outputDir targets the shared payload
// tree (prod); standalone defaults to the repo-root build-opencode-dev/ dir (the
// Task-5 dev-loop target, gitignored). Both assemble tasks share this var, mirroring
// codex: the dev render reads from its OWN fixed render/opencode-dev stage, so
// assembleOpencodeDev must NOT be invoked with -Pbuild.outputDir (that would only
// move the Sync destination, not the render stage it reads). Prod is the variant
// that takes the property.
val outputDir = (findProperty("build.outputDir") as String?)
    ?.let { file(it) }
    ?: rootProject.layout.projectDirectory.dir("build-opencode-dev").asFile

// ---------------------------------------------------------------------------
// assembleOpencodeDev → build-opencode-dev/. Sync is the sole writer (overlap-
// immune, prunes stale files). Merges: the dev render (skills/start-dev +
// hooks/install-shipsmooth.sh + dist/session-start-config.json), the compiled
// plugin JS into plugin/, and the dev-token package.json.
// ---------------------------------------------------------------------------
val devRenderStage = renderModule.layout.buildDirectory.dir("render/opencode-dev").get().asFile
val devPkgStage = layout.buildDirectory.dir("stage/opencode-dev-pkg").get().asFile
val packageJsonDev = registerPackageJson("packageJsonDev", devTokens, devPkgStage)

tasks.register<Sync>("assembleOpencodeDev") {
    group = "assemble"
    description = "Assemble the opencode-dev plugin payload into build-opencode-dev/."
    dependsOn(renderModule.tasks.named("renderOpencodeDev"), compileTs, packageJsonDev)
    // skills/ stays at the payload root — OpenCode discovers <config-dir>/skills/<name>.
    from(devRenderStage) { include("skills/**") }
    // The plugin reads its config + installer relative to its OWN module dir, so
    // co-locate them under plugin/ next to index.js (keeps the plugin self-contained;
    // OpenCode auto-discovers plugin/*.js and ignores the sibling dist/ + hooks/).
    from(devRenderStage) { include("hooks/**", "dist/**"); into("plugin") }
    from(tsDir.dir("dist")) { into("plugin") }
    from(devPkgStage)
    into(outputDir)
}

// ---------------------------------------------------------------------------
// assembleOpencodeProd → <build.outputDir> (pass -Pbuild.outputDir). Sync is the
// sole writer (overlap-immune; mirrors assembleOpencodeDev with prod tokens + the
// prod render: start frontmatter, prod description, experimental skills hidden).
// The compiled plugin JS is identical across dev/prod (one TS build), so both
// variants source it from the module's own src/main/ts/dist.
// ---------------------------------------------------------------------------
val prodRenderStage = renderModule.layout.buildDirectory.dir("render/opencode-prod").get().asFile
val prodPkgStage = layout.buildDirectory.dir("stage/opencode-prod-pkg").get().asFile
val packageJsonProd = registerPackageJson("packageJsonProd", prodTokens, prodPkgStage)

tasks.register<Sync>("assembleOpencodeProd") {
    group = "assemble"
    description = "Assemble the opencode-prod plugin payload into <build.outputDir> (pass -Pbuild.outputDir)."
    dependsOn(renderModule.tasks.named("renderOpencodeProd"), compileTs, packageJsonProd)
    // skills/ stays at the payload root — OpenCode discovers <config-dir>/skills/<name>.
    from(prodRenderStage) { include("skills/**") }
    // Co-locate the plugin's config + installer under plugin/ next to index.js, so the
    // plugin resolves them relative to its own module dir (proven in Task 1/11 de-risk).
    from(prodRenderStage) { include("hooks/**", "dist/**"); into("plugin") }
    from(tsDir.dir("dist")) { into("plugin") }
    from(prodPkgStage)
    into(outputDir)
}

// devBuild: local dev-loop convenience (Task 5). Unlike claude's devBuild, the
// opencode dev render writes to its OWN fixed render/opencode-dev stage and
// assembleOpencodeDev already defaults to repo-root build-opencode-dev/, so no
// nested GradleBuild / build.outputDir retargeting is needed — a no-arg
// assembleOpencodeDev lands the full payload in the right dir. This alias just gives
// OpenCode the same uniformly-named entry point the other hosts expose.
// Run: ./gradlew :harness:opencode:devBuild
//   then: OPENCODE_CONFIG_DIR=$(pwd)/build-opencode-dev opencode
val devBuild by tasks.registering {
    group = "assemble"
    description = "Assemble the full opencode-dev payload into repo-root build-opencode-dev/ for local dev/test."
    dependsOn(tasks.named("assembleOpencodeDev"))
}
