# Plan 11: Add dev build workflow for local plugin testing

## Context
The code-flow plugin is distributed via the `pramodb-plugins` marketplace, pinned to a git SHA. To test any change to SKILL.md or other plugin files, the author must push to GitHub and update the marketplace SHA. This makes iteration slow.

We need a local dev build workflow so the author can build a dev variant of the plugin locally and test it immediately in a separate sample app project, without publishing.

**Linear feature issue:** PB-196

### Reference projects
- **obra/superpowers**: ships `.claude-plugin/marketplace.json` with `"source": "./"` defining a `superpowers-dev` marketplace
- **cadamsdotcom/CodeLeash**: uses `--plugin-dir` CLI flag for local dev

## Design decisions
- **Build tool:** Maven with Java 21 (source + target compatibility), anticipating the build will grow
- **Dev plugin name:** `dev-code-flow` — distinct from production `code-flow`, so both can coexist without conflict
- **Dev marketplace name:** `code-flow-dev`
- **Build output:** `build/` directory (gitignored), contains a complete plugin with `skills/dev-code-flow/SKILL.md`
- **Name substitution:** Maven resource filtering replaces `${plugin.skillName}` in copied files, turning `code-flow` → `dev-code-flow` in the SKILL.md trigger line
- **No files outside the repo are modified** — README documents how the user should configure their local Claude setup

## Tasks

### Task 1: Create `pom.xml` with Maven resource filtering
- Java 21 source compatibility and compilation target
- Use `maven-resources-plugin` to copy `skills/code-flow/SKILL.md` → `build/skills/dev-code-flow/SKILL.md` with filtering enabled
- Define property `plugin.skillName=dev-code-flow` for the dev profile
- Copy `.claude-plugin/plugin.json` to `build/.claude-plugin/plugin.json` (filtered if needed)
- Generate `build/.claude-plugin/marketplace.json` with `"name": "code-flow-dev"`, `"source": "./"`, plugin name `dev-code-flow`
- `make dev` equivalent: `mvn process-resources` (or a Maven profile)
- Build output directory: `build/`

### Task 2: Add `${plugin.skillName}` placeholder to SKILL.md trigger line
- Replace the literal `code-flow:code-flow` trigger in SKILL.md with `${plugin.skillName}:${plugin.skillName}`
- Maven resource filtering substitutes this during the build
- The source SKILL.md is no longer directly usable as a skill (it contains `${...}`) — the production build must also go through Maven. Add a `prod` profile that sets `plugin.skillName=code-flow` and outputs to a separate directory (or the same `build/` with the production name)

### Task 3: Create `.claude-plugin/marketplace.json` template
Template for the dev marketplace definition, placed in source tree and copied to `build/` during build:
```json
{
  "name": "code-flow-dev",
  "description": "Development marketplace for code-flow plugin",
  "owner": {
    "name": "Pramod Biligiri",
    "url": "https://github.com/pramodbiligiri/code-flow"
  },
  "plugins": [
    {
      "name": "${plugin.skillName}",
      "description": "Agent coding workflow (dev build)",
      "source": "./",
      "author": {
        "name": "Pramod Biligiri"
      },
      "category": "development"
    }
  ]
}
```

### Task 4: Add `build/` to `.gitignore`

### Task 5: Add Development section to README.md
Document:
1. **Prerequisites:** Java 21, Maven
2. **Build dev version:** `mvn process-resources`
3. **Register dev marketplace** in `~/.claude/settings.json`:
   - Add `code-flow-dev` to `extraKnownMarketplaces` with `"source": "directory"` pointing to `build/`
   - Enable `dev-code-flow@code-flow-dev` in `enabledPlugins`
4. **Usage:** start Claude in any project — `/dev-code-flow` invokes the dev build
5. **Switching back:** disable `dev-code-flow@code-flow-dev`, re-enable `code-flow@pramodb-plugins`
6. **Key notes:** restart Claude to pick up changes after rebuilding

### Task 6 (future): Migrate SKILL.md to a templating language
When the number of substitutions or conditional sections grows beyond what Maven resource filtering handles cleanly, switch SKILL.md to a proper templating language. Not in scope for this plan — tracked as a future task.

## Files to create/modify
- **Create:** `pom.xml`
- **Create:** `.gitignore` (or append to existing)
- **Create:** source template for `marketplace.json` (in a resources dir for Maven to filter)
- **Modify:** `skills/code-flow/SKILL.md` — add `${plugin.skillName}` placeholder on trigger line
- **Modify:** `README.md` — add Development section

## Verification
1. Run `mvn process-resources`
2. Confirm `build/skills/dev-code-flow/SKILL.md` exists with `dev-code-flow:dev-code-flow` trigger
3. Confirm `build/.claude-plugin/marketplace.json` has plugin name `dev-code-flow`
4. Confirm `build/.claude-plugin/plugin.json` exists with correct metadata
