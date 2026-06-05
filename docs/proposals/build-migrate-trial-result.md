# Gradle Skills Trial — Result (plan-71, Phase 0)

> **Status:** Trial complete. This records the objective outcome of porting
> `skills/pkg` to Gradle on the throwaway branch `t/71-gradle-skills-trial`, as
> the go/no-go gate defined in [`build-migrate.md`](build-migrate.md) §"Migration
> path and sequence", Phase 0. The final go/no-go decision is the human's; this
> document gives the evidence and a recommendation.

## What was built

A complete Gradle build for `skills/pkg` (the best-case module), covering the
full Maven `mvn -Pdev` / `mvn -Pgemini-dev` pipeline:

- **`buildSrc` convention plugin** — JDK 25 toolchain, UTF-8, `mavenLocal`,
  JUnit Jupiter, JaCoCo. (The Semeru *vendor* pin was dropped: `skills/pkg` has
  no jlink/SCC/OpenJ9 dependency, so any JDK 25 suffices. The vendor pin remains
  relevant only for `cli`/`packaging`.)
- **`node-gradle` TS pipeline** (`compileTs`) — real content-hashed input
  tracking, replacing the `dist -nt session-start.ts` mtime hack.
- **JTE staging + precompile** (`stageJte` + `gg.jte.gradle` generate mode) —
  collapses the antrun + jte-maven + build-helper chain.
- **Render targets** (`renderClaudeDev`, `renderGeminiDev`) — `JavaExec` tasks
  sharing one `RenderSpec` value object (the drift guard), replacing the Maven
  profile matrix.
- **Tests + coverage** — all six existing test classes under `./gradlew test`.

## Parity (the acceptance gate)

**Byte-for-byte parity confirmed** on a clean run of both pipelines. All twelve
`Target`-rendered files (six per variant × two variants) are identical between
Maven and Gradle:

| Variant | Files compared | Result |
|---|---|---|
| claude-dev (`mvn -Pdev` vs `renderClaudeDev`) | 4× SKILL.md, hooks.json, session-start-config.json | ✓ identical |
| gemini-dev (`mvn -Pgemini-dev` vs `renderGeminiDev`) | same six | ✓ identical |

Additionally, the 61 JTE-precompiled template classes are **FQN-identical** to
the Maven output (`gg.jte.generated.precompiled.*`), and all 6 test classes
(57 tests) pass with 98.7% line coverage on hand-written code.

**Scope note:** "parity" here means *`Target`'s rendered output*. The compiled
`dist/*.js` (`session-start.js`, `adm-zip-bundle.js`) are copied by a separate
packaging step (`skip.copy-dist`), not produced by `Target`, so they are outside
this module's render parity. `compileTs` does produce them in `scripts/dist`.

Two real wiring bugs were caught **by the parity diff** during the trial (and
fixed): `plugin.version` resolving to `"unspecified"`, and `repoRoot` resolving
one directory short. This is itself a data point — the parity harness works.

## The dev loop (the actual prize)

| Scenario | Maven (`mvn -Pdev compile`) | Gradle |
|---|---|---|
| No-op rebuild | **re-renders all templates every time** | everything UP-TO-DATE, ~1s, renders nothing |
| Edit one `.ts` | re-runs only if it's the one watched sentinel file | `compileTs` re-runs (all of `scripts/tasks` hashed) |
| Edit one `.jte.md` | re-renders everything | re-stages → re-generates → re-compiles → re-renders |
| Build claude + gemini | two passes (profiles mutually exclusive) | `./gradlew renderClaudeDev renderGeminiDev` — one pass |

The incrementality and non-exclusive targets are exactly the wins the proposal
predicted for this module, and they are real and felt.

## The honest cost

**Line count went *up*, not down.** This matches the proposal's caveat that the
`buildSrc`/`settings`/`RenderSpec` overhead claws back the per-file savings:

| | Lines |
|---|---:|
| Maven `pom.xml` | 255 |
| Gradle `build.gradle.kts` | 149 |
| + convention plugin | 61 |
| + `RenderSpec.kt` | 43 |
| + `settings.gradle.kts` | 4 |
| + `buildSrc/build.gradle.kts` | 13 |
| **Gradle total** | **270** |

So the single build file a skills dev opens is shorter (149 vs 255) and more
readable, but the *aggregate* build config is slightly larger. Line count is not
a reason to migrate; the proposal already said so.

Other costs observed:
- **Cold build is slower** — first run pays Kotlin DSL script compilation +
  `buildSrc` build (~2 min the first time; sub-second warm).
- **Gradle 9 friction** — `junit-platform-launcher` no longer transitive (had to
  declare `testRuntimeOnly`); minor but the kind of papercut that recurs.
- **A whole second toolchain** (`/opt/gradle`, daemon, Kotlin DSL) to learn and
  maintain alongside Maven for the duration of any migration.

## Assessment & recommendation

**Objectively:** parity is met, the dev-loop incrementality is real, the
non-exclusive render targets work, and the build file a developer edits is
clearly nicer. **But** the aggregate config grew, cold builds are slower, and
the proposal's own framing is that `skills/pkg` is the *best case* — the modules
with the real risk (`core` dagger-shade + `module-info` re-injection, `cli`
jlink + SCC) get little of this upside and carry all the danger.

The trial delivered what it promised *for this module*. Whether that clears the
asymmetric bar — "clearly better, enough to justify migrating `core`/`cli`/
`packaging` too" — is the human go/no-go call.

> **Go / No-Go decision:** _(to be recorded by the human)_
>
> - [ ] **Go** — proceed to Phase 1 (structure: `settings.gradle.kts` + `buildSrc`
>       for the full reactor, `core`/`cli` compiling).
> - [ ] **No-Go** — abandon the migration, delete the trial branch, keep Maven
>       (optionally apply the in-place Maven enhancements from `build-migrate.md`
>       §"Enhancements within the Maven system" instead).
