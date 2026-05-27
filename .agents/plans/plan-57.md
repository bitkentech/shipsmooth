# Plan 57 — Rename top-level package to `io.bitken.ss` + introduce `conf` package

## Context

Backlog issue: none — structural refactor. `io.bitken.shipsmooth.tasks` is too narrow (CLI now covers ledger, services, soon api/web) and too long (`shipsmooth` repeated everywhere is IDE clutter). `ss` is the stable abbreviation — product names change, package names shouldn't need to follow.

Additionally, `di/` and `stability/` are both configuration-time concerns (wiring the object graph, gating features). Grouping them under `conf/` makes that intent explicit. `stability/` contains only a single `FeatureFlags` interface, so it dissolves into `conf/` directly — no sub-package needed.

Target changes:
- Rename every occurrence of `io.bitken.shipsmooth.tasks` → `io.bitken.ss` across all Java source files, `module-info.java`, `app/pom.xml`, and resource files
- Move `di/` → `conf/` and `stability/FeatureFlags` → `conf/FeatureFlags` (dissolve `stability/` package)
- Update all references to `stability.FeatureFlags` → `conf.FeatureFlags`

No logic changes. Pure mechanical rename + reorganisation.

## Scope

Packages touched (old → new):

| Old | New |
|-----|-----|
| `io.bitken.shipsmooth.tasks` | `io.bitken.ss` |
| `io.bitken.shipsmooth.tasks.cmd` | `io.bitken.ss.cmd` |
| `io.bitken.shipsmooth.tasks.di` | `io.bitken.ss.conf` |
| `io.bitken.shipsmooth.tasks.stability` | _(dissolved into `io.bitken.ss.conf`)_ |
| `io.bitken.shipsmooth.tasks.git` | `io.bitken.ss.git` |
| `io.bitken.shipsmooth.tasks.integration` | `io.bitken.ss.integration` |
| `io.bitken.shipsmooth.tasks.ledger` | `io.bitken.ss.ledger` |
| `io.bitken.shipsmooth.tasks.service` | `io.bitken.ss.service` |
| `io.bitken.shipsmooth.tasks.workflow` | `io.bitken.ss.workflow` |
| `io.bitken.shipsmooth.tasks.jaxb` (generated) | `io.bitken.ss.jaxb` |

The JAXB-generated package is controlled by `<packageName>` in `app/pom.xml`. The native-image resource dir is named after the module and must also be renamed.

## Risk analysis

### Task 1: Rename package declarations in all Java source files [Medium]
Mechanical sed across all `.java` files in `src/main/java` and `src/test/java`. Covers both `package` statements and `import` statements. Risk: a missed file leaves a compile error; easy to catch at build time. Medium because of volume (76 files).

### Task 2: Move `di/` to `conf/` and dissolve `stability/` into `conf/` [Medium]
- Rename `di/` directory to `conf/`, update package declarations inside
- Move `stability/FeatureFlags.java` into `conf/`, update its package declaration
- Update all import references (`tasks.di.*` → `ss.conf.*`, `tasks.stability.FeatureFlags` → `ss.conf.FeatureFlags`) across `cmd/`, `TasksCli.java`, and `module-info.java`
- Delete the now-empty `stability/` directory
Medium because two packages are merging and all referencing files must be updated consistently.
*Depends-on: 1*

### Task 3: Update `module-info.java` [Low]
Module name + `opens` directives (replace `tasks.commands` → `ss.cmd`, `tasks.di` → `ss.conf`, remove `tasks.stability`).
*Depends-on: 1,2*

### Task 4: Update `app/pom.xml` [Low]
String replacements in `<mainClass>`, `<packageName>`, native-image args, jlink args.

### Task 5: Rename and update native-image resource directory [Low]
Rename `META-INF/native-image/io.bitken.shipsmooth.tasks/` → `META-INF/native-image/io.bitken.ss/`. Update string literals inside `native-image.properties` and `reflect-config.json`.

### Task 6: Verify full build and tests pass [Low]
Run `mvn compile` then `mvn test -pl app -am`. Fix any stragglers.
*Depends-on: 1,2,3,4,5*

## Risk-sorted task order

1. Task 1 — Rename package declarations in all Java source files [Medium]
2. Task 2 — Move `di/` to `conf/` and dissolve `stability/` into `conf/` [Medium]
3. Task 3 — Update `module-info.java` [Low]
4. Task 4 — Update `app/pom.xml` [Low]
5. Task 5 — Rename and update native-image resource directory [Low]
6. Task 6 — Verify full build and tests pass [Low]