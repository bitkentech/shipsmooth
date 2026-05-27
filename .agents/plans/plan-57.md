# Plan 57 — Rename top-level package to `io.bitken.ss` + package restructure

## Context

Backlog issue: none — structural refactor. `io.bitken.shipsmooth.tasks` is too narrow (CLI now covers ledger, services, soon api/web) and too long (`shipsmooth` repeated everywhere is IDE clutter). `ss` is the stable abbreviation — product names change, package names shouldn't need to follow.

Structural changes alongside the rename:

1. **`cmd` → `cli`**: more descriptive — says what the package *is* rather than abbreviating "command". `TasksCli.java` moves into `cli/` and is renamed to `Shipsmooth.java` — it's the product entry point and the class name should reflect that. `TasksCli` as a class name is also updated to `Shipsmooth` throughout.

2. **`conf/` package**: `di/` and `stability/` are both configuration-time concerns. Merge into `conf/`. `stability/` has only one class (`FeatureFlags`) so it dissolves directly — no sub-package.

3. **`workflow/integration/` sub-package**: `integration/` and `workflow/` are heavily coupled (`WorkflowServiceImpl` imports 8 classes from `integration/`). The boundary enforces nothing. Make `integration/` a sub-package of `workflow/` to reflect the true dependency direction. `IntegrationOptions` and `IntegrationResult` stay in `workflow/` — they are part of the public service API.

4. **Rename CLI command to `shipsmooth`**: the Picocli root command name and jlink launcher are currently `tasks` / `shipsmooth-tasks`. Rename both to `shipsmooth`. Note: the distributed runtime binary (`runtime-0.3.10/bin/shipsmooth-tasks`) is a release artifact — renaming it requires a version bump and is out of scope for this plan.

No logic changes. Pure mechanical rename + reorganisation.

## Scope

Packages touched (old → new):

| Old | New |
|-----|-----|
| `io.bitken.shipsmooth.tasks` | `io.bitken.ss` |
| `io.bitken.shipsmooth.tasks.cmd` | `io.bitken.ss.cli` |
| `io.bitken.shipsmooth.tasks.di` | `io.bitken.ss.conf` |
| `io.bitken.shipsmooth.tasks.stability` | _(dissolved into `io.bitken.ss.conf`)_ |
| `io.bitken.shipsmooth.tasks.git` | `io.bitken.ss.git` |
| `io.bitken.shipsmooth.tasks.integration` | `io.bitken.ss.workflow.integration` |
| `io.bitken.shipsmooth.tasks.ledger` | `io.bitken.ss.ledger` |
| `io.bitken.shipsmooth.tasks.service` | `io.bitken.ss.service` |
| `io.bitken.shipsmooth.tasks.workflow` | `io.bitken.ss.workflow` |
| `io.bitken.shipsmooth.tasks.jaxb` (generated) | `io.bitken.ss.jaxb` |

Files moving between packages:

| File | From | To |
|------|------|----|
| `TasksCli.java` → `Shipsmooth.java` | `io.bitken.ss` (root) | `io.bitken.ss.cli` |

The JAXB-generated package is controlled by `<packageName>` in `app/pom.xml`. The native-image resource dir is named after the module and must also be renamed.

## Final folder structure (after all tasks)

```
io/bitken/ss/
├── AgentsLayout.java
├── cli/
│   ├── Shipsmooth.java
│   ├── AddComment.java
│   ├── AddDeviation.java
│   ├── Claim.java
│   ├── HasSpec.java
│   ├── Init.java
│   ├── Integrate.java
│   ├── Ledger.java
│   ├── LedgerRecordCommit.java
│   ├── LedgerRecordPatchIntegrated.java
│   ├── LedgerResolverComplete.java
│   ├── LedgerWatch.java
│   ├── ProjectUpdate.java
│   ├── SetCommit.java
│   ├── Show.java
│   ├── UpdateStatus.java
│   ├── WorkerBase.java
│   ├── WorkerCleanup.java
│   ├── WorkerFinish.java
│   └── WorkerInit.java
├── conf/
│   ├── AppComponents.java
│   ├── ServicesModule.java
│   └── FeatureFlags.java
├── git/
├── jaxb/  (generated)
├── ledger/
├── service/
└── workflow/
    ├── ConsoleProgressReporter.java
    ├── DefaultProcessRunner.java
    ├── IntegrationOptions.java
    ├── IntegrationResult.java
    ├── ProcessRunner.java
    ├── ProgressReporter.java
    ├── Transaction.java
    ├── WorkflowErrorCode.java
    ├── WorkflowException.java
    ├── WorkflowService.java
    ├── WorkflowServiceImpl.java
    └── integration/
        ├── IntegrationDefaults.java
        ├── IntegrationLedger.java
        ├── IntegrationOrder.java
        ├── LedgerSubagentRunner.java
        ├── PromptBuilder.java
        ├── Resolver.java
        ├── ResolverContext.java
        ├── SubagentResolver.java
        ├── SubagentRunner.java
        └── TaskOrderInput.java
```

## Risk analysis

### Task 1: Rename package declarations in all Java source files [Medium]
Mechanical sed across all `.java` files in `src/main/java` and `src/test/java`. Covers both `package` statements and `import` statements. Risk: a missed file leaves a compile error; easy to catch at build time. Medium because of volume (76 files).

### Task 2: Rename `cmd/` to `cli/`, move and rename `TasksCli` → `Shipsmooth` [Medium]
- Rename `cmd/` directory to `cli/`, update package declarations inside from `ss.cmd` → `ss.cli`
- Move `TasksCli.java` from root into `cli/`, rename file to `Shipsmooth.java`
- Rename class `TasksCli` → `Shipsmooth` and update all references (imports, test files, DI wiring)
- Update `module-info.java` opens directive (`ss.cmd` → `ss.cli`)
- Update `<mainClass>` in `pom.xml` to `io.bitken.ss.cli.Shipsmooth`
Medium because `TasksCli` is referenced from `pom.xml`, tests, and the DI module.
*Depends-on: 1*

### Task 3: Move `di/` to `conf/` and dissolve `stability/` into `conf/` [Medium]
- Rename `di/` directory to `conf/`, update package declarations inside
- Move `stability/FeatureFlags.java` into `conf/`, update its package declaration
- Update all import references (`ss.di.*` → `ss.conf.*`, `ss.stability.FeatureFlags` → `ss.conf.FeatureFlags`) across `cli/`, and `module-info.java`
- Delete the now-empty `stability/` directory
Medium because two packages are merging and all referencing files must be updated consistently.
*Depends-on: 1*

### Task 4: Move `integration/` under `workflow/` as a sub-package [Medium]
- Move all 10 classes from `ss.integration` into `ss.workflow.integration` (physically into `workflow/integration/` dir)
- Update package declarations in moved files
- Update all import references in `workflow/WorkflowServiceImpl.java`, `workflow/IntegrationOptions.java`, and any test files
- `IntegrationOptions` and `IntegrationResult` stay in `workflow/` — they are part of the public service API
- Delete the now-empty `integration/` directory
Medium because it changes the package of 10 classes and their imports in several files.
*Depends-on: 1*

### Task 5: Rename `shipsmooth-tasks` → `shipsmooth` everywhere [Low]
Full rename across all files that reference the binary or command name:

**App code:**
- `TasksCli.java`: `rootSpec.name("tasks")` → `rootSpec.name("shipsmooth")`
- `app/pom.xml`: jlink launcher name and all script args (`shipsmooth-tasks` → `shipsmooth`)
- `packaging/pom.xml`: any packaging references
- `packaging/src/main/java/.../PackageRuntime.java`: binary name string
- `packaging/src/main/java/.../PublishRelease.java`: binary name string
- `packaging/src/test/java/.../PackageRuntimeTest.java`: test string literals
- `integrations/common/src/main/java/.../BuildProfile.java`: binary name string
- `integrations/common/src/test/java/.../BuildProfileTest.java` and `ResourceBuilderIntegrationTest.java`: test string literals

**Scripts and config:**
- `devel/scripts/package-tasks-java.sh`
- `devel/scripts/experiment-startup-matrix.sh`
- `devel/scripts/experiment-jlink-with-shr.sh`
- `.claude/settings.local.json`

**Skills:**
- `build/skills/start-dev/SKILL.md`
- `build/skills/experimental-start-parallel-dev/SKILL.md`

**Docs (update references so they stay accurate):**
- `README.md`
- `DEVELOPMENT.md`
- `docs/proposals/service-layer.md`
- `docs/proposals/xdg-local-bin-launcher.md`
- `docs/proposals/feature-flags-typed-visitable.md`
- `docs/proposals/goal-oriented-impl.md`
- `docs/observations/2026-04-27-openj9-scc-startup-correction.md`
- `docs/decisions/2026-04-27-jlink-startup-optimisation.md`
- `integrations/claude/src/main/resources/windows/README.md`

Note: the distributed runtime binary in `runtime-0.3.10/bin/shipsmooth-tasks` is a release artifact — it will be renamed when the next runtime version is cut. The SKILL.md runtime invocation path keeps the old binary name for the current runtime version; update the invocation example to reflect the new name for future releases.
*Depends-on: 2*

### Task 6: Update `module-info.java` [Low]
Module name + `opens` directives: `ss.cmd` → `ss.cli`, `ss.di` → `ss.conf`, remove `ss.stability`, add `ss.workflow.integration` if needed by picocli or jackson.
*Depends-on: 1,2,3,4*

### Task 7: Update `app/pom.xml` [Low]
String replacements in `<mainClass>` (`io.bitken.ss.cli.Shipsmooth`), `<packageName>` (`io.bitken.ss.jaxb`), native-image args, jlink args.
*Depends-on: 2*

### Task 8: Rename and update native-image resource directory [Low]
Rename `META-INF/native-image/io.bitken.shipsmooth.tasks/` → `META-INF/native-image/io.bitken.ss/`. Update string literals inside `native-image.properties` and `reflect-config.json`.

### Task 9: Verify full build and tests pass [Low]
Run `mvn compile` then `mvn test -pl app -am`. Fix any stragglers.
*Depends-on: 1,2,3,4,5,6,7,8*

### Task 10: Rename `packaging/` module package `io.bitken.shipsmooth.dist` → `io.bitken.ss.dist` [Low]
Files:
- `packaging/src/main/java/io/bitken/shipsmooth/dist/{PackageRuntime,PublishRelease,ValidateRelease}.java` — update `package` declarations
- `packaging/src/test/java/io/bitken/shipsmooth/dist/{PackageRuntimeTest,PublishReleaseTest,ValidateReleaseTest}.java` — update `package` declarations
- `packaging/pom.xml` — update `<mainClass>` references (6 occurrences)
- Move physical directories: `io/bitken/shipsmooth/dist/` → `io/bitken/ss/dist/`
*Depends-on: 9*

### Task 11: Rename `integrations/common/` module package `io.bitken.shipsmooth.resources` → `io.bitken.ss.resources` [Low]
Files:
- `integrations/common/src/main/java/io/bitken/shipsmooth/resources/{BuildProfile,PluginModel,ResourceBuilder}.java` — update `package` declarations
- `integrations/common/src/test/java/io/bitken/shipsmooth/resources/{BuildProfileTest,ResourceBuilderIntegrationTest}.java` — update `package` declarations
- `integrations/common/pom.xml` — update `<mainClass>` reference
- `.jte.md` templates that reference `io.bitken.shipsmooth.resources.PluginModel` — update to `io.bitken.ss.resources.PluginModel`
- Move physical directories: `io/bitken/shipsmooth/resources/` → `io/bitken/ss/resources/`
*Depends-on: 9*

### Task 13: Delete unused TypeScript files from `integrations/common/scripts/tasks/` [Low]
TypeScript is no longer used now that the CLI is Java-only. The following files are dead code and should be removed:
`add-comment.ts`, `add-deviation.ts`, `hello.ts`, `init.ts`, `project-update.ts`, `set-commit.ts`, `show.ts`, `types.ts`, `update-status.ts`, `xml-utils.ts`.

Keep: `session-start.ts` (still active), `adm-zip-bundle.d.ts` (type declaration used by session-start), `plan-tasks.xsd` (JAXB source for `app/pom.xml`).
*Depends-on: 12*

### Task 12: Update remaining docs and stragglers [Low]
- `docs/decisions/2026-04-27-jlink-startup-optimisation.md` — references to old package names
- `docs/observations/2026-04-28-jaxb-jlink-bloat.md` — references to old package names
- `docs/proposals/package-split-core-clients.md` — references to old package names
- `docs/proposals/service-layer.md` — references to old package names
- Any other files surfaced by `grep -r "io.bitken.shipsmooth" .`
*Depends-on: 10,11*

## Risk-sorted task order

1. Task 1 — Rename package declarations in all Java source files [Medium]
2. Task 2 — Rename `cmd/` to `cli/` and move `TasksCli` into it [Medium]
3. Task 3 — Move `di/` to `conf/` and dissolve `stability/` into `conf/` [Medium]
4. Task 4 — Move `integration/` under `workflow/` as a sub-package [Medium]
5. Task 5 — Rename CLI command to `shipsmooth` [Low]
6. Task 6 — Update `module-info.java` [Low]
7. Task 7 — Update `app/pom.xml` [Low]
8. Task 8 — Rename and update native-image resource directory [Low]
9. Task 9 — Verify full build and tests pass [Low]
10. Task 10 — Rename `packaging/` module package to `io.bitken.ss.dist` [Low]
11. Task 11 — Rename `integrations/common/` module package to `io.bitken.ss.resources` [Low]
12. Task 12 — Update remaining docs and stragglers [Low]
13. Task 13 — Delete unused TypeScript files from `integrations/common/scripts/tasks/` [Low]