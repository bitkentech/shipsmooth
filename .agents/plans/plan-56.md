# Plan 56: Maven Module Restructuring

## Context

The current module names (`plugin-tasks-java`, `plugin-skill`, `plugin-node`,
`plugin-resources`, `plugin-dist`, `plugin-devel`) are implementation-flavoured.
As shipsmooth grows to support multiple coding harnesses (Claude, Gemini, OpenCode,
future) and a web client alongside the CLI, the structure needs to reflect the
actual domain: an in-process app core, shared integration sources, per-harness
integration packages, and a packaging step.

Two design axes drive this:
1. **Local delivery** — CLI and web server access the domain in-process (same JVM)
2. **Remote integration** — Claude, Gemini etc. receive packaged artifacts (skills,
   hooks, manifests) that describe how an external agent runtime calls the domain

## Target Structure

```
shipsmooth/
├── app/                    (was: plugin-tasks-java)
│   ├── core/               domain + service layer (package split only, not Maven modules)
│   ├── cli/                Picocli commands
│   └── web/                HTTP handlers (future)
│
├── integrations/           (conceptual grouping — not a Maven parent module yet)
│   ├── common/             (was: plugin-skill + plugin-node merged)
│   │   ├── jte-src/        shared JTE skill templates
│   │   └── scripts/        shared TypeScript hook scripts
│   ├── claude/             (was: plugin-resources claude portion)
│   └── gemini/             (was: plugin-resources gemini portion)
│
└── packaging/              (was: plugin-dist)
devel/                      (was: plugin-devel)
```

## Tasks

### Task 1: Rename plugin-devel to devel [Low]

- `mv plugin-devel/ devel/`
- `devel/pom.xml`: `artifactId` → `devel`
- Root `pom.xml`: `<module>plugin-devel</module>` → `<module>devel</module>`
- No other module depends on it — safest rename to do first.

### Task 2: Rename plugin-tasks-java to app [Low]

- `mv plugin-tasks-java/ app/`
- `app/pom.xml`: `artifactId` → `app`
- Root `pom.xml`: `<module>plugin-tasks-java</module>` → `<module>app</module>`
- Root `pom.xml` lines 133, 153, 195: `plugin-tasks-java/target/jlink-image` → `app/target/jlink-image`
- Scripts (path/comment updates only, not critical for build):
  - `scripts/experiment-startup-matrix.sh` (4 occurrences)
  - `scripts/package-tasks-java.sh` (5 occurrences)
  - `scripts/experiment-jlink-with-shr.sh` (6 occurrences)

*Depends-on: 1*

### Task 3: Rename plugin-node to hooks [Low]

- `mv plugin-node/ hooks/`
- `hooks/pom.xml`: `artifactId` → `hooks`
- Root `pom.xml`: `<module>plugin-node</module>` → `<module>hooks</module>`
- `app/pom.xml` line 136: `../plugin-node/src/main/scripts/tasks/plan-tasks.xsd` → `../hooks/src/main/scripts/tasks/plan-tasks.xsd`
- `plugin-dist/pom.xml`: dependency `plugin-node` → `hooks`; fix 4 path references to `plugin-node/dist`, `plugin-node/src/main/resources`, `plugin-node/src/main/scripts/tasks`
- `scripts/test-gemini-hook.sh` (2 occurrences)

*Depends-on: 2*

### Task 4: Rename plugin-skill to skills [Low]

- `mv plugin-skill/ skills/`
- `skills/pom.xml`: `artifactId` → `skills`
- Root `pom.xml`: `<module>plugin-skill</module>` → `<module>skills</module>`
- `plugin-dist/pom.xml`: dependency `plugin-skill` → `skills`

*Depends-on: 3*

### Task 5: Rename plugin-dist to packaging [Low]

- `mv plugin-dist/ packaging/`
- `packaging/pom.xml`: `artifactId` → `packaging`
- Root `pom.xml`: `<module>plugin-dist</module>` → `<module>packaging</module>`
- `packaging/pom.xml`: fix error message referencing `plugin-tasks-java` → `app`

*Depends-on: 4*

### Task 6: Split plugin-resources into integrations/claude and integrations/gemini [Medium]

- Create `integrations/claude/pom.xml` — extract the `claude` + `windows` profile
  copy-resources executions from `plugin-resources/pom.xml`; `artifactId` →
  `integration-claude`; `packaging` → `pom`
- Move `plugin-resources/src/main/resources/claude-plugin/` →
  `integrations/claude/src/main/resources/claude-plugin/`
- Move `plugin-resources/src/main/resources/windows/` →
  `integrations/claude/src/main/resources/windows/`
- Create `integrations/gemini/pom.xml` — extract the `gemini` + `gemini-dev` profile
  copy-resources executions; `artifactId` → `integration-gemini`; `packaging` → `pom`
- Move `plugin-resources/src/main/resources/gemini-extension/` →
  `integrations/gemini/src/main/resources/gemini-extension/`
- Root `pom.xml`: replace `<module>plugin-resources</module>` with
  `<module>integrations/claude</module>` and `<module>integrations/gemini</module>`
- `packaging/pom.xml`: dependency `plugin-resources` → `integration-claude`
  (Gemini packaging is self-contained in `integrations/gemini`)
- Remove `plugin-resources/`

*Depends-on: 5*

### Task 7: Merge skills and hooks sources into integrations/common [Medium]

Consolidate the shared JTE templates (currently in `skills/`) and shared TypeScript
hook scripts (currently in `hooks/`) into a single `integrations/common/` Maven
module. This makes "shared integration source" a first-class concept.

- Create `integrations/common/` with a new `pom.xml` absorbing the build logic
  from both `skills/pom.xml` and `hooks/pom.xml`
- Move JTE template sources: `skills/src/main/jte-src/` → `integrations/common/jte-src/`
- Move Java source: `skills/src/main/java/` → `integrations/common/src/main/java/`
- Move TypeScript sources: `hooks/src/main/scripts/` → `integrations/common/scripts/`
- Update all downstream references (`integrations/claude/`, `integrations/gemini/`,
  `packaging/`, `app/` XSD path)
- Remove `skills/` and `hooks/` modules
- Root `pom.xml`: replace those two `<module>` entries with `<module>integrations/common</module>`

*Depends-on: 6*

### Task 8: Move top-level scripts/ into devel/ [Low]

The top-level `scripts/` directory contains developer shell utilities
(release, smoke test, experiments). These belong under `devel/` alongside
other development-only tooling.

- `mv scripts/ devel/scripts/`
- Update `DEVELOPMENT.md`: replace `./scripts/` with `./devel/scripts/` in
  all usage examples (smoke-gemini, release-gemini invocations)

*Depends-on: 7*

### Task 9: Fix REPO_ROOT paths in moved scripts [Low]

All scripts in `devel/scripts/` compute `REPO_ROOT` as `$SCRIPT_DIR/..`,
which resolved to the repo root when scripts lived at `scripts/` (one level
deep). Now at `devel/scripts/` (two levels deep), they must go up two levels.

Scripts using `${SCRIPT_DIR}/..`:
- `experiment-jlink-with-shr.sh`
- `package-tasks-java.sh`
- `release.sh`
- `release-gemini.sh`
- `experiment-startup-matrix.sh`

Change each: `"${SCRIPT_DIR}/.."` → `"${SCRIPT_DIR}/../.."`

Scripts using inline `$(dirname "${BASH_SOURCE[0]}")/..`:
- `test-gemini-hook.sh`
- `smoke-gemini.sh`

Change each: `$(dirname "${BASH_SOURCE[0]}")/.."` → `$(dirname "${BASH_SOURCE[0]}")/../../.."` (note: these
inline forms don't use `SCRIPT_DIR`, so they need three `..` segments
total: `dirname` gives the dir, then `/../..` goes up two levels)

Also fix `smoke-gemini.sh` line that invokes `test-gemini-hook.sh` via
`$REPO_ROOT/scripts/test-gemini-hook.sh` → `$REPO_ROOT/devel/scripts/test-gemini-hook.sh`

*Depends-on: 8*

### Task 10: Manual testing of dev build, Gemini build, and mvn clean [Low]

*Depends-on: 9*

## Verification

After tasks 1–5 (renames):
```bash
mvn -Pdev compile
mvn -Pgemini compile
```

After task 6 (integration split):
```bash
mvn -Pdev compile
mvn -Pgemini compile
# Check outputs exist:
ls build/.claude-plugin/plugin.json
ls build/skills/start/SKILL.md
ls build-gemini/gemini-extension.json
ls build-gemini/skills/start/SKILL.md
```

After task 7 (common consolidation):
```bash
mvn -Pdev package -DskipTests
mvn -Pgemini package -DskipTests
# Spot-check SKILL.md contains harness-specific agent commands
grep -q "agent-resolver-call" build/skills/start/SKILL.md
```

After tasks 8–9 (scripts move):
```bash
ls devel/scripts/
# Verify REPO_ROOT resolves correctly:
bash -c 'SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"; echo $REPO_ROOT' -- devel/scripts/release.sh
```
