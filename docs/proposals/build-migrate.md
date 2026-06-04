# Proposal: Migrate the shipsmooth Multi-Module Build from Maven to Gradle (Kotlin DSL)

> **Status:** Draft for evaluation. Rewritten against the repository as of version
> `0.3.13` (post plan-68/69 restructure). Every module name, class name, JPMS module
> name, JDK version, and build step below is taken from the current `pom.xml` files and
> source tree, not from a generic Maven→Gradle template.

## Recommendation summary (TL;DR)

**Do not do a full Maven→Gradle migration now.** The build works, and the genuine wins
are narrow and concentrated; the costs and risks are large and concentrated in the worst
places.

* **The real wins are small and local:** proper Node/TS incrementality and cleaner JTE
  wiring (both in `skills/pkg`, helping the dev inner loop), plus terser, more readable
  build config for resource-copy-heavy modules — the **claude/gemini integration**
  files in particular collapse from ~30-line nested-XML copy blocks to a few lines of
  `Copy`/`expand`. See [Per-developer impact](#per-developer-impact-speed--authoring).
* **The justifications people reach for are weak:** line count drops only ~25–35% (and
  the new `buildSrc`/`settings.gradle.kts` claw some back); "type safety" doesn't apply
  to the four stringly-typed `main()` entrypoints; perf gains are really the
  incrementality win in disguise.
* **The costs/risks are large and land on the hardest modules:** dagger shade +
  `module-info` re-injection, the hand-pinned jlink runtime module-path, 5-platform
  cross-jlink + SCC launcher, JAXB/Dagger-APT/templated codegen, and the ~12-variable
  render matrix. `cli` and `packaging` (38% of build lines) barely shrink and are the
  riskiest to port.
* **Most of the speed-up is available without migrating:** add `mvnd` (warm daemon) and
  the Maven build-cache extension for ~none of the migration risk.

**Recommended path:**

1. **Now:** if speed is the pain, add `mvnd` + Maven build-cache to the existing build.
2. **If still motivated:** run **Phase 0** only — port `skills/pkg` (Node/TS + JTE +
   `Target` render) to Gradle side-by-side and measure incrementality against
   `mvn compile`. This validates the one real win cheaply.
3. **Only if Phase 0 clearly pays off:** commit to the full, parity-gated migration in
   the sequence below. Otherwise, consider moving only `skills/pkg` (speed + authoring
   win) and the `claude`/`gemini` integration modules (low-risk, pure copy/filter →
   large readability win), leaving `cli`/`core`/`packaging` on Maven.

The remainder of this document is the detailed, repo-accurate basis for that
recommendation.

## Motivation

The shipsmooth build is a polyglot reactor: seven Maven modules that compile Java
(JPMS), generate code (JAXB `xjc`, Dagger APT, templated sources), render Markdown via
JTE, compile TypeScript via npm, link platform-specific OpenJ9/Semeru `jlink` runtimes,
and assemble four distribution layouts (Claude dev/prod, Gemini dev/prod, Windows). A
Gradle migration is *plausible* but not obviously worth it; this document states the
real wins, the real costs, and — critically — the parts of the existing build that any
migration **must** reproduce faithfully or it will regress.

### Where Gradle would genuinely help

* **Real incrementality for the Node/TS step.** `skills/pkg/pom.xml` and `devtools/pom.xml`
  currently gate npm/tsc with brittle shell heuristics
  (`[ -d dist ] && [ dist -nt tasks/session-start.ts ] || npm run build`). This only
  checks one sentinel source file's mtime. Gradle's `node-gradle` plugin with explicit
  `inputs.dir`/`outputs.dir` would model the whole `scripts/tasks` → `scripts/dist`
  graph and skip correctly.
* **Cleaner JTE wiring.** Rendering today takes a four-plugin chain in `skills/pkg`:
  `maven-antrun` (copy `start/`, `experimental/`, `shared/` and rename `.jte.md → .jte`)
  → `jte-maven-plugin` (precompile) → `build-helper` (`add-source`) → `maven-compiler`.
  `gg.jte.gradle` wires generated sources straight into `compileJava`, collapsing the
  middle two steps (the rename step still has to be reproduced — see Risks).
* **Target-scoped tasks instead of a property matrix.** The five profiles
  (`dev`, `prod`, `windows`, `gemini`, `gemini-dev`) exist mainly to set the render
  variables consumed by `io.bitken.ss.resources.Target`. Explicit Gradle tasks
  (`renderClaudeDev`, `renderGeminiProd`, …) make the variants discoverable and
  non-exclusive (today you cannot build Claude and Gemini in one reactor pass).

### Where Gradle does **not** help (honest accounting)

* **The four packaging entrypoints stay stringly-typed.** `Target` (render),
  `ValidateRelease`, `PackageRuntime`, and `PublishRelease` (all in `packaging/` and
  `skills/pkg/`) are `main(String[])` programs invoked via `exec:java@<id>` with ~12
  system properties. In Gradle these become `JavaExec` + `systemProperty(...)` — exactly
  as stringly-typed. The "compile-time type safety" win applies only to build-script
  glue, which is a small fraction of the surface.
* **The hard JPMS/jlink problems do not disappear.** Dagger shading into `core`,
  `module-info.class` re-injection, `useModulePath=false` for tests, and the
  hand-pinned runtime module-path all have to be reproduced verbatim. Gradle changes the
  syntax, not the underlying constraints.

### Expected line-count impact (weak justification)

The current build is **1,732 lines across 9 `pom.xml` files**:

| File | Lines | Expected after migration | Why |
|---|---:|---:|---|
| `packaging/pom.xml` | 343 | ~290 | Mostly irreducible logic: 5 `PackageRuntime`/`ValidateRelease`/`PublishRelease` execs with sysprops. Args carry over near 1:1. |
| `cli/pom.xml` | 316 | ~270 | 5 platform jlink invocations + SCC launcher heredoc + smoke tests. Content, not ceremony. |
| `skills/pkg/pom.xml` | 255 | ~150 | JTE 4-plugin chain collapses; Node gains real inputs/outputs. |
| `pom.xml` (root) | 225 | ~70 | `pluginManagement` pinning → `buildSrc`; 5 profiles → typed `RenderSpec` + variant tasks. |
| `core/pom.xml` | 225 | ~160 | Every codegen/shade step stays; only the `<execution>` wrapping is saved. |
| `gemini/pom.xml` | 161 | ~80 | Pure resource filtering → `Copy { expand() }`. |
| `claude/pom.xml` | 124 | ~70 | Same as gemini. |
| `devtools/pom.xml` | 56 | ~30 | npm/tsc with real inputs/outputs. |
| `skills/pom.xml` | 27 | ~2 | Becomes lines in `settings.gradle.kts`. |
| **Total** | **1,732** | **~1,120–1,250** | |

That is roughly a **25–35% reduction** — *not* the 50%+ that "eliminate XML
boilerplate" pitches imply, and with two important caveats:

* **The estimate excludes new files the migration adds**: `settings.gradle.kts` and the
  `buildSrc` convention plugin (~40–60 lines together). The true net reduction is smaller
  than a naive pom-vs-`build.gradle.kts` diff.
* **The savings are concentrated in the low-risk modules** (`claude`, `gemini`, root
  profiles). The two largest files — `cli` (316) and `packaging` (343), together **38% of
  all build lines** — barely shrink, because their bulk is genuine build *logic* (jlink
  args, launcher script, per-platform exec blocks), not XML overhead. These are also the
  highest-risk modules to port.

Conclusion: line count *in aggregate* is a **weak** argument. But the lines that *do*
shrink are concentrated in the build files that integration and skills developers
actually open and edit — which is the readability point made in the next section, and a
better argument than the total-line delta.

### Per-developer impact (speed + authoring) {#per-developer-impact-speed--authoring}

"Is Gradle better for the average developer?" depends entirely on which part of the tree
they touch, and on **two** axes: rebuild speed *and* how readable/editable the build
config is when they open it. The two axes point different ways for different people:

| Works on… | Build file today | Rebuild speed | Authoring / readability | Net |
|---|---|---|---|---|
| **Skills** (61 `.jte.md` + TS hooks) | `skills/pkg` 255L | ✅ real — incremental JTE/TS replaces the `dist -nt` mtime hack | ✅ JTE 4-plugin chain → terse | ✅✅ best case |
| **Claude / Gemini integration** (no code; JSON/TOML/MD) | 124L / 161L | ➖ none (copy/filter is fast either way) | ✅ **large** — ~30-line nested-XML `<execution>` blocks → ~4 lines of `Copy { from/into }` + `expand()` | ✅ readability win (not speed) |
| **CLI** (49 Java files) | `cli` 316L | ➖ marginal; **jlink relink is unhelped** | ➖ jlink args / launcher stay verbose in any DSL | ➖ mostly neutral, inherits port risk |
| **Web** (not yet in reactor) | — | ✅ if JS/TS bundler; ➖ if Java service | ✅ Kotlin DSL over XML | ✅ likely, if JS-heavy |
| **Core** (55 Java files) | `core` 225L | ➖ neutral (`javac` incremental in both) | ➖ codegen + shade + module-info config stays fiddly | ➖ neutral |

Two corrections to a naive "perf-only" read:

* **Integration devs are not "no difference."** On *speed* they aren't, but their entire
  build file is copy-and-filter boilerplate — exactly what Gradle shrinks most. They edit
  it whenever they add a command or manifest field, so the readability win recurs.
* **The win is partly offset by a learning cost.** Every dev must learn Gradle, the
  Kotlin DSL, the daemon, task names, and `buildSrc` conventions instead of Maven XML
  they may already know. "Simpler syntax" pays off only after that one-time cost.

The pattern: the **authoring** benefit lands on skills + integration devs (the most
common editors, lowest-risk modules); the **speed** benefit lands on skills (+ a JS web
module). cli/core devs gain little and carry the porting risk. This is the same
conclusion as the cost analysis — value concentrates in `skills/pkg`, with the
integration modules a strong, low-risk secondary candidate.

### Performance impact (moderate justification)

Net assessment: **the dev inner loop — which is most of the actual work, on `skills`,
`cli`, `core`, and the upcoming web module — stands to gain the most, via
incrementality; the occasional release pipeline is tool-agnostic and Gradle can't speed
it up; cold/CI builds are a wash or slightly slower.** Performance is a secondary
argument, and much of the dev-loop gain is achievable in Maven without migrating (see
the `mvnd`/build-cache caveat below).

Distinguish the two builds, because they have very different cost profiles:

* **Dev inner loop (`mvn compile`)** — what you run constantly while working on
  `skills`, `cli`, `core` (and the upcoming web module). This forks only the
  `skills/pkg` pipeline (gated npm/tsc, JTE rename + precompile, **one** `exec:java`
  for the `Target` render) plus `core` codegen (JAXB `xjc`, Dagger APT, templated
  source). That is roughly **one forked JVM plus the usually-skipped npm/tsc** — small.
* **Release pipeline** — run occasionally and mostly **by hand**. The six
  `packaging` `exec:java` runs (`ValidateRelease`, 4× `PackageRuntime`,
  `PublishRelease`) have **no lifecycle phase binding**; they fire only on explicit
  `mvn exec:java@<id>` invocations (see `DEVELOPMENT.md`), and `packaging` is not on the
  dev compile path at all. The 5× `jlink` links + SCC launcher + smoke tests live in
  `cli` under `-Pjlink package`. Together this is ~23 forked processes whose wall-clock
  is dominated by JVM/process startup plus the work inside — linking five Semeru jlink
  images, `npm install`, `tsc`.

The key consequence: **the heavy, tool-agnostic cost lives in the release pipeline you
rarely run, while the dev loop you run all day is exactly the npm/tsc/JTE work where
Gradle's incrementality helps.** Gradle makes the release jlink/exec work no faster
(jlink takes the same time whoever invokes it), but that path is not your hot loop.

Factor by factor:

* **Startup — Gradle worse by default, fixable.** Maven cold-starts a JVM each run
  (~0.5–1s). The Gradle daemon keeps a warm JVM + cached model, so 2nd+ runs skip
  startup. But the *first* run of a session pays daemon spin-up **and Kotlin DSL script
  compilation**, which is heavier than parsing XML — so cold builds can be slower.
* **Incrementality — Gradle clearly better; the real win.** Today `mvn compile` gates
  npm/tsc on a brittle `dist -nt session-start.ts` mtime check and re-renders JTE every
  time. Gradle task input/output tracking + build cache would skip unchanged TS and JTE
  work. This overlaps exactly with the Node/TS + JTE argument above and is what you'd
  feel in the edit → `compile` → restart loop.
* **Parallelism — narrow Gradle edge.** The reactor is largely linear
  (`core → cli → packaging`), but the 5 sequential jlink links could run as independent
  parallel tasks. Real, but only on full release builds.
* **Configuration cache — Gradle better but fragile here.** Heavy `Exec`/`JavaExec`
  use (which this build has a lot of) is easy to make config-cache-incompatible; expect
  to fight it.
* **Memory — Gradle worse.** The daemon holds a resident JVM (hundreds of MB) between
  builds; Maven does not. Matters on a constrained box.

**The strongest counter-argument:** the current Maven build has **no `mvnd` daemon and
no build-cache extension configured** — so this is not a fair baseline. Adding `mvnd`
(warm-daemon startup) and the Maven build-cache extension (incrementality) would capture
most of the speed-up for ~none of the migration risk. If perf were the *only* goal, that
is the cheaper path.

Bottom line: performance is a **moderate** argument that is really the incrementality
argument in different clothes. It lands squarely on the dev inner loop (the work you
actually do most — `skills`/`cli`/`core`/web), not on the rarely-run release pipeline —
and it is partly available without migrating via `mvnd` + the Maven build-cache
extension.

---

## Background: the actual module graph

```
shipsmooth (pom, root)
├── core            io.bitken.ss.core  — JAXB xjc, Dagger APT, templated sources, shade(jlink)
├── cli             io.bitken.ss.cli   — picocli CLI + jlink image (5 platforms) + SCC launcher + smoke tests
├── skills          (pom aggregator)
│   └── pkg         skills-pkg         — npm/tsc, JTE render engine, Target main class
├── claude          integration-claude — filters .claude-plugin/ + marketplace.json
├── gemini          integration-gemini — filters gemini-extension.json, commands/
├── packaging       packaging          — copy dist, ValidateRelease/PackageRuntime/PublishRelease
└── devtools        devtools           — dev-only TS helper scripts
```

Key facts the migration must respect (verified in-tree):

* **JDK is Semeru/OpenJ9 25** (`/opt/installers/jdk-semeru/jdk-25.0.2+10`), not 21. The
  `jlink` invocations use `--compress zip-9` (new syntax) and add `openj9.sharedclasses`.
* **JPMS module names** are `io.bitken.ss.core` and `io.bitken.ss.cli`. The launcher is
  `shipsmooth=io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth`.
* **Dagger is `requires static`** in `core/module-info.java` and is **shaded into the
  core jar** under the `jlink` profile, because `DaggerAppComponents` is generated into
  `core` and references `dagger.internal.*` at runtime, and `core` must not read `cli`.
  Shade strips `module-info.class`, so it is re-injected with `jar --update`.
* **The runtime module-path is a hand-pinned list of ~15 jars** from the local repo
  (`/opt/mvn/repository` per `~/.m2/settings.xml`), not `runtimeClasspath`. The exact set
  is in `cli/pom.xml`'s `jlink.runtime.module.path`.
* **`core` runs three codegen steps**: `jaxb2-maven-plugin xjc` from
  `src/main/resources/plan-tasks.xsd` → package `io.bitken.ss.jaxb`; Dagger annotation
  processing; and `templating-maven-plugin filter-sources` (generates `Build` with
  `VERSION` / `EXPERIMENTAL_BUILD`).
* **Tests run on the classpath, not the module path** (`useModulePath=false` in `core`
  and `cli`) to avoid a JPMS split-package `ResolutionException` between the shaded
  dagger classes and the real dagger module.

---

## Strategic objectives

* **Faithful parity first.** The migration is successful only if it produces
  byte-equivalent (or behaviour-equivalent) `build/`, `build-gemini/`, `build-windows/`,
  and `runtime-<ver>/` payloads. Parity is the acceptance test, not a nice-to-have.
* **Real Node/TS incrementality.** Replace the `-nt` mtime hacks with task input/output
  declarations.
* **Explicit, non-exclusive variant tasks.** Make Claude/Gemini/Windows builds
  selectable tasks rather than a mutually-exclusive profile matrix.

---

## Architectural blueprint

### 1. `settings.gradle.kts`

```kotlin
rootProject.name = "shipsmooth"

include("core")
include("cli")
include("skills:pkg")
include("claude")
include("gemini")
include("packaging")
include("devtools")
```

### 2. `buildSrc` convention plugin (JDK 25 / Semeru toolchain)

```kotlin
// buildSrc/src/main/kotlin/shipsmooth.java-conventions.gradle.kts
plugins { java }

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.IBM) // Semeru/OpenJ9; required for SCC + zip-9 jlink
    }
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
repositories { mavenCentral() }

dependencies { testImplementation("org.junit.jupiter:junit-jupiter:5.10.2") }

tasks.test {
    useJUnitPlatform()
    // Mirror Maven surefire useModulePath=false: shaded dagger + real dagger module
    // collide as a JPMS split package in the test fork. Tests need no module layer.
    modularity.inferModulePath.set(false)
}
```

> Note: the Gradle toolchain must resolve to the *same* Semeru JDK the `jlink` step uses.
> If auto-provisioning can't find Semeru, pin it via
> `org.gradle.java.installations.paths=/opt/installers/jdk-semeru/jdk-25.0.2+10`.

---

## Detailed module transformation

### `core` — codegen + conditional shade

The non-negotiable parts: JAXB `xjc`, Dagger APT, the templated `Build` source, and the
`jlink`-only shade + `module-info` re-injection.

```kotlin
// core/build.gradle.kts
plugins {
    id("shipsmooth.java-conventions")
    id("com.github.bjornvester.xjc") version "1.8.2"   // wraps xjc; emits io.bitken.ss.jaxb
    id("com.gradleup.shadow") version "8.3.5" apply false // only wired under -PjlinkBuild
}

dependencies {
    implementation("jakarta.xml.bind:jakarta.xml.bind-api:4.0.2")
    implementation("org.glassfish.jaxb:jaxb-runtime:4.0.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
    compileOnly("com.google.dagger:dagger:2.59.2")          // requires static dagger
    annotationProcessor("com.google.dagger:dagger-compiler:2.59.2")
    implementation("jakarta.inject:jakarta.inject-api:2.0.1")
}

xjc {
    xsdDir.set(layout.projectDirectory.dir("src/main/resources")) // plan-tasks.xsd
    packageName.set("io.bitken.ss.jaxb")
}

// Generate Build.java (VERSION, EXPERIMENTAL_BUILD) — replaces templating-maven-plugin.
val generateBuildConstants by tasks.registering(Copy::class) {
    from("src/main/java-templates")          // Build.java template with @tokens@
    into(layout.buildDirectory.dir("generated/sources/build-constants/io/bitken/ss"))
    expand("project.version" to project.version, "experimental.enabled" to experimentalEnabled())
}
sourceSets.main { java.srcDir(generateBuildConstants.map { it.destinationDir }) }

// jlink build only: shade dagger into core, then re-inject module-info.class.
if (project.hasProperty("jlinkBuild")) {
    apply(plugin = "com.gradleup.shadow")
    // configure shadowJar to include com.google.dagger:dagger + javax.inject only,
    // strip META-INF/*.SF|DSA|RSA, then a follow-up Exec runs:
    //   $SEMERU/bin/jar --update --file <shaded.jar> module-info.class
    // (Shade strips module-info; core must remain a named module on the link path.)
}
```

### `cli` — jlink image, SCC launcher, smoke tests

This is the old `app` work in the proposal; it lives in `cli`. The launcher module is
`io.bitken.ss.cli`, compression is `zip-9`, and the OpenJ9 SCC launcher
(`-Xquickstart -Xshareclasses`) is mandatory — the smoke tests run *through it*.

```kotlin
// cli/build.gradle.kts
plugins {
    id("shipsmooth.java-conventions")
    application
}

dependencies {
    implementation(project(":core"))
    implementation("info.picocli:picocli:4.7.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")
}

val semeruHome = providers.gradleProperty("jlink.exec.home")
    .orElse("/opt/installers/jdk-semeru/jdk-25.0.2+10")

// Hand-pinned runtime module path (exact jars from /opt/mvn/repository), mirroring
// cli/pom.xml's jlink.runtime.module.path. NOT runtimeClasspath — the local-repo jar
// layout is depended upon and the shaded core jar must replace the plain core jar.
fun runtimeModulePath(): String = TODO("port the explicit ~15-jar list verbatim")

val platforms = mapOf(
    "linux-x64"    to "/opt/installers/jdk-semeru/jdk-25.0.2+10",
    "darwin-x64"   to "/opt/installers/jdk-semeru-mac-x64/Contents/Home",
    "darwin-arm64" to "/opt/installers/jdk-semeru-mac-arm64/Contents/Home",
    "windows-x64"  to "/opt/installers/jdk-semeru-win-x64/jdk-25.0.2+10",
)

platforms.forEach { (name, jmodsHome) ->
    tasks.register<Exec>("jlinkImage_$name") {
        // depends on shaded core jar (-PjlinkBuild) + this module's jar
        outputs.dir(layout.buildDirectory.dir("jlink-image-$name"))
        commandLine(
            "$semeruHome/bin/jlink".let { providers.gradleProperty("jlink.exec.home").map { h -> "$h/bin/jlink" }.getOrElse(it) },
            "--module-path", "${runtimeModulePath()}:$jmodsHome/jmods",
            "--add-modules", "io.bitken.ss.cli,openj9.sharedclasses",
            "--launcher", "shipsmooth=io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth",
            "--no-header-files", "--no-man-pages",
            "--compress", "zip-9",
            "--output", layout.buildDirectory.dir("jlink-image-$name").get().asFile.absolutePath,
        )
    }
}

// OpenJ9 shared-class-cache launcher (mandatory; smoke tests run through it).
val writeSccLauncher by tasks.registering {
    // emit build/scc-launcher/shipsmooth: a shell wrapper invoking the JRE with
    //   -Xquickstart -Xshareclasses:name=shipsmooth_v<ver>,cacheDir=...,nonfatal
    //   --module-path <runtime> -m io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth "$@"
    // TODO: cross-platform variant (matches the TODO already in cli/pom.xml).
}
```

### `skills:pkg` — Node/TS + JTE render engine

The single biggest *real* win. `Target` (`io.bitken.ss.resources.Target`) renders the
JTE templates into `build.outputDir` and consumes the full variable set.

```kotlin
// skills/pkg/build.gradle.kts
plugins {
    id("shipsmooth.java-conventions")
    id("com.github.node-gradle.node") version "7.1.0"
    id("gg.jte.gradle") version "3.1.15"
}

node { download.set(false) } // use system Node, matching exec-maven-plugin behaviour

dependencies {
    implementation("gg.jte:jte:3.1.15")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

// Real incrementality, replacing  [ dist -nt tasks/session-start.ts ] || npm run build
val compileTs by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "build"))
    inputs.dir("scripts/tasks")
    inputs.file("scripts/package.json")
    outputs.dir("scripts/dist")
}

// Reproduce the antrun rename: copy sibling start/, experimental/, shared/ .jte.md
// into a staging dir and rename .jte.md -> .jte before jte precompiles them.
val stageJte by tasks.registering(Copy::class) {
    listOf("start", "experimental", "shared").forEach { dir ->
        from(rootProject.layout.projectDirectory.dir(dir)) { into(dir) }
    }
    rename("(.*)\\.jte\\.md", "$1.jte")
    into(layout.buildDirectory.dir("jte-src"))
}

jte {
    sourceDirectory.set(stageJte.map { it.destinationDir.toPath() })
    contentType.set(gg.jte.ContentType.Plain)
    generate()
}
```

The `Target` render run becomes a `JavaExec` task per variant, passing the same system
properties the POM does today (`build.outputDir`, `build.env`, `build.platform`,
`plugin.base.name`, `plugin.skill.start.basename`, `plugin.version`,
`plugin.description`, `skill.frontmatter`, `shipsmooth.jlink.dir`, `plugin.hook.command`,
`experimental.enabled`, `plugin.repo.name`). These must be modelled as a typed
`RenderSpec` extension so the variants stay in sync; see Risks.

### `claude` / `gemini` — manifest filtering

Pure resource filtering (`maven-resources-plugin filtering=true`). In Gradle these are
`Copy` tasks with `expand(...)` (Groovy `${}` interpolation; mind JSON braces). `claude`
filters `.claude-plugin/*` + `marketplace.json`; `gemini` filters
`gemini-extension.json` and copies `commands/` (different source dir for `gemini` vs
`gemini-dev`). Windows adds a `README.md` copy.

### `packaging` — assembly + the three release entrypoints

`copy-dist` (compiled JS minus `*.test.js`) + `JavaExec` for `ValidateRelease`,
`PackageRuntime` (per platform: `linux-x64`, `darwin-x64`, `darwin-arm64`, `win32-x64`),
and `PublishRelease`. The dev profile's "verify jlink image exists" guard becomes a task
precondition. These stay `JavaExec`-with-args; no type-safety gain, parity only.

---

## Variant strategy (replacing the profile matrix)

| Maven profile | Gradle equivalent |
|---|---|
| `dev` (default) | `:packaging:assembleClaudeDev` task chain, `build.outputDir=build/` |
| `prod` | `:packaging:assembleClaudeProd` |
| `windows` | `:packaging:assembleWindows` (+ jlink `windows-x64`, README, bundled JRE/.bat) |
| `gemini` | `:packaging:assembleGeminiProd`, `build.outputDir=build-gemini/` |
| `gemini-dev` | `:packaging:assembleGeminiDev`, `build.outputDir=build-gemini-dev/` |

Each variant task fixes its `RenderSpec` (the ~12 vars) and `outputDir`, so they are no
longer mutually exclusive and can run in one invocation.

---

## Automated verification

Mirror the existing `verify`-phase smoke tests, which run **through the SCC launcher**
from the repo root:

```kotlin
// cli/build.gradle.kts
val jlinkSmokeHelp by tasks.registering(Exec::class) {
    dependsOn(writeSccLauncher, "jlinkImage_linux-x64")
    commandLine(layout.buildDirectory.file("scc-launcher/shipsmooth").get().asFile.absolutePath, "--help")
    isIgnoreExitValue = false
}
val jlinkSmokeShow by tasks.registering(Exec::class) {
    dependsOn(writeSccLauncher, "jlinkImage_linux-x64")
    workingDir(rootProject.projectDir)
    commandLine(layout.buildDirectory.file("scc-launcher/shipsmooth").get().asFile.absolutePath,
                "plan", "show", "--plan", "27")
    isIgnoreExitValue = false
}
```

---

## Migration path and sequence

Side-by-side, parity-gated. **Do not delete any `pom.xml` until byte/behaviour parity is
proven for all four payloads.**

1. **Phase 0 — Spike (de-risk the real wins).** Port only `skills:pkg` (Node/TS + JTE +
   `Target` render for Claude-dev) to Gradle alongside Maven. Compare `build/` output
   against `mvn compile`. This validates the incrementality and JTE claims cheaply before
   committing to the hard modules.
2. **Phase 1 — Structure.** Add `settings.gradle.kts` + `buildSrc` with the Semeru-25
   toolchain; get `core` and `cli` *compiling* (no jlink), tests on classpath.
3. **Phase 2 — Codegen parity.** Reproduce JAXB `xjc`, Dagger APT, and the `Build`
   constants generator in `core`; diff generated sources against Maven's.
4. **Phase 3 — jlink + shade.** Port the conditional dagger shade + `module-info`
   re-injection, the hand-pinned runtime module-path, all five platform images, the SCC
   launcher, and the smoke tests. Diff image contents against the Maven images.
5. **Phase 4 — Assembly + release.** Port `claude`/`gemini`/`packaging` variant tasks and
   the `ValidateRelease`/`PackageRuntime`/`PublishRelease` execs. Diff all four payloads
   and `runtime-<ver>/`.
6. **Phase 5 — Cutover.** Update `DEVELOPMENT.md`, `devtools/scripts/smoke-gemini.sh`,
   and any CI to Gradle tasks; remove the `pom.xml` files only after parity sign-off.

---

## Risks and open questions

* **Dagger shade + `module-info` re-injection** is the highest-risk item. Gradle's shadow
  plugin also strips `module-info`; the `jar --update` re-inject step must run with the
  *Semeru* `jar`. Get this working in a throwaway branch before trusting the estimate.
* **Hand-pinned runtime module-path.** Porting `runtimeClasspath` instead of the explicit
  jar list will silently change the image and may reintroduce the JPMS split-package
  problem. The explicit list (including the *shaded* core jar in place of the plain one)
  must be carried over verbatim.
* **Render-variable drift.** The ~12 `Target` properties must be a single typed spec
  shared by all variants, or Claude/Gemini/Windows outputs will diverge. This is the main
  correctness risk in the "kill the profiles" objective.
* **Toolchain resolution.** Gradle must select Semeru 25, not a generic JDK 25, or the
  SCC launcher and `zip-9` jlink break.
* **Payoff vs. cost.** The dev inner loop is already `mvn compile` and is fast. The
  measurable wins (Node/TS incrementality, JTE wiring) are concentrated in `skills:pkg`;
  the costly, risky work is in `core`/`cli`. Phase 0 should decide whether the full
  migration is justified or whether only `skills:pkg` is worth moving.
