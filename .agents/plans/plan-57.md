# Plan 57 — Rename top-level package from `tasks` to root

## Context

Backlog issue: none yet — this is a structural refactor driven by the observation that `io.bitken.shipsmooth.tasks` is too narrow. The CLI now covers ledger, services, and soon api/web layers. The `tasks` segment is a historical artefact.

Target: rename every occurrence of `io.bitken.shipsmooth.tasks` → `io.bitken.shipsmooth` across:
- All Java source files (56 in main, ~20 in test)
- `module-info.java` (package declarations, `opens`, module name)
- `app/pom.xml` (mainClass, packageName, native-image args, jlink args)
- `src/main/resources/META-INF/native-image/io.bitken.shipsmooth.tasks/` — directory rename + content
- String literals embedding the old package name (native-image.properties, reflect-config.json)

No logic changes. No file moves between subpackages. Pure mechanical rename.

## Scope

Packages touched (old → new):

| Old | New |
|-----|-----|
| `io.bitken.shipsmooth.tasks` | `io.bitken.shipsmooth` |
| `io.bitken.shipsmooth.tasks.cmd` | `io.bitken.shipsmooth.cmd` |
| `io.bitken.shipsmooth.tasks.di` | `io.bitken.shipsmooth.di` |
| `io.bitken.shipsmooth.tasks.git` | `io.bitken.shipsmooth.git` |
| `io.bitken.shipsmooth.tasks.integration` | `io.bitken.shipsmooth.integration` |
| `io.bitken.shipsmooth.tasks.ledger` | `io.bitken.shipsmooth.ledger` |
| `io.bitken.shipsmooth.tasks.service` | `io.bitken.shipsmooth.service` |
| `io.bitken.shipsmooth.tasks.stability` | `io.bitken.shipsmooth.stability` |
| `io.bitken.shipsmooth.tasks.workflow` | `io.bitken.shipsmooth.workflow` |
| `io.bitken.shipsmooth.tasks.jaxb` (generated) | `io.bitken.shipsmooth.jaxb` |

The JAXB-generated package is controlled by `<packageName>` in `app/pom.xml` and by the `package` attribute in `plan-tasks.xsd` (if present) — both must be updated.

## Open questions

- The Java module name in `module-info.java` is currently `io.bitken.shipsmooth.tasks`. Rename to `io.bitken.shipsmooth`. This affects the jlink launcher args (`-m io.bitken.shipsmooth/...`).
- The native-image resource dir is named after the module: `META-INF/native-image/io.bitken.shipsmooth.tasks/` → rename to `META-INF/native-image/io.bitken.shipsmooth/`.

## Risk analysis

### Task 1: Rename package declarations in all Java source files [Medium]
Mechanical sed across all `.java` files in `src/main/java` and `src/test/java`. Risk: a missed file leaves a compile error; easy to catch. Medium because of volume (76 files) and the need to handle both `package` statements and `import` statements correctly.

### Task 2: Update `module-info.java` [Low]
Three changes: module name, `opens` directives. Small file, easy to verify.

### Task 3: Update `app/pom.xml` [Low]
String replacements in `<mainClass>`, `<packageName>`, native-image args, jlink args. No logic change.

### Task 4: Rename and update native-image resource directory [Low]
Rename `META-INF/native-image/io.bitken.shipsmooth.tasks/` → `META-INF/native-image/io.bitken.shipsmooth/`. Update string literals inside `native-image.properties` and `reflect-config.json`.

### Task 5: Update XSD `<packageName>` and verify JAXB generation [Medium]
The JAXB package name is set in `pom.xml` (already covered in Task 3) and possibly in an XSD binding file. Need to confirm the generated `jaxb` package compiles under the new name. Medium because JAXB codegen is a separate Maven phase and errors only surface at `generate-sources`.

### Task 6: Verify full build and tests pass [Low]
Run `mvn compile` then `mvn test -pl app -am`. Fix any stragglers. This is the integration checkpoint.

## Risk-sorted task order

High→Med→Low, with dependencies respected:

1. Task 1 — Rename package declarations in all Java source files [Medium]
2. Task 5 — Update XSD `<packageName>` and verify JAXB generation [Medium]
3. Task 2 — Update `module-info.java` [Low]
4. Task 3 — Update `app/pom.xml` [Low]
5. Task 4 — Rename and update native-image resource directory [Low]
6. Task 6 — Verify full build and tests pass [Low]