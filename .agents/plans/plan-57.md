# Plan 57 — Rename top-level package to `io.bitken.ss`

## Context

Backlog issue: none — this is a structural refactor. `io.bitken.shipsmooth.tasks` is too narrow (CLI now covers ledger, services, soon api/web) and too long (`shipsmooth` repeated everywhere is IDE clutter). `ss` is the stable abbreviation — product names change, package names shouldn't need to follow.

Target: rename every occurrence of `io.bitken.shipsmooth.tasks` → `io.bitken.ss` across:
- All Java source files (56 in main, ~20 in test)
- `module-info.java` (module name, `opens` directives)
- `app/pom.xml` (mainClass, packageName, native-image args, jlink args)
- `src/main/resources/META-INF/native-image/io.bitken.shipsmooth.tasks/` — directory rename + content
- String literals embedding the old package name (native-image.properties, reflect-config.json)

No logic changes. Pure mechanical rename.

## Scope

Packages touched (old → new):

| Old | New |
|-----|-----|
| `io.bitken.shipsmooth.tasks` | `io.bitken.ss` |
| `io.bitken.shipsmooth.tasks.cmd` | `io.bitken.ss.cmd` |
| `io.bitken.shipsmooth.tasks.di` | `io.bitken.ss.di` |
| `io.bitken.shipsmooth.tasks.git` | `io.bitken.ss.git` |
| `io.bitken.shipsmooth.tasks.integration` | `io.bitken.ss.integration` |
| `io.bitken.shipsmooth.tasks.ledger` | `io.bitken.ss.ledger` |
| `io.bitken.shipsmooth.tasks.service` | `io.bitken.ss.service` |
| `io.bitken.shipsmooth.tasks.stability` | `io.bitken.ss.stability` |
| `io.bitken.shipsmooth.tasks.workflow` | `io.bitken.ss.workflow` |
| `io.bitken.shipsmooth.tasks.jaxb` (generated) | `io.bitken.ss.jaxb` |

The JAXB-generated package is controlled by `<packageName>` in `app/pom.xml` — update there. The native-image resource dir is named after the module and must also be renamed.

## Risk analysis

### Task 1: Rename package declarations in all Java source files [Medium]
Mechanical sed across all `.java` files in `src/main/java` and `src/test/java`. Risk: a missed file leaves a compile error; easy to catch. Medium because of volume (76 files) — both `package` statements and `import` statements must be updated.

### Task 2: Update XSD `<packageName>` and verify JAXB generation [Medium]
The JAXB package name is set via `<packageName>` in `app/pom.xml`. Need to confirm the generated `jaxb` package compiles under the new name. Medium because JAXB codegen runs at `generate-sources` and errors only surface there.
*Depends-on: 1*

### Task 3: Update `module-info.java` [Low]
Module name + `opens` directives. Small file, easy to verify.
*Depends-on: 1*

### Task 4: Update `app/pom.xml` [Low]
String replacements in `<mainClass>`, `<packageName>`, native-image args, jlink args.

### Task 5: Rename and update native-image resource directory [Low]
Rename `META-INF/native-image/io.bitken.shipsmooth.tasks/` → `META-INF/native-image/io.bitken.ss/`. Update string literals inside `native-image.properties` and `reflect-config.json`.

### Task 6: Verify full build and tests pass [Low]
Run `mvn compile` then `mvn test -pl app -am`. Fix any stragglers.
*Depends-on: 1,2,3,4,5*

## Risk-sorted task order

1. Task 1 — Rename package declarations in all Java source files [Medium]
2. Task 2 — Update XSD `<packageName>` and verify JAXB generation [Medium]
3. Task 3 — Update `module-info.java` [Low]
4. Task 4 — Update `app/pom.xml` [Low]
5. Task 5 — Rename and update native-image resource directory [Low]
6. Task 6 — Verify full build and tests pass [Low]