// shipsmooth OpenCode plugin — internal helpers (plan-86 Task 10/11).
//
// These pure helpers live OUTSIDE the plugin entry module on purpose. OpenCode
// (verified on 1.17.9) loads a plugin file by importing it and treating EVERY
// named export as a plugin factory — it invokes each export with the plugin
// context object. Helpers like `readConfig`/`installerPath` call `path.join` on
// their first argument, so if OpenCode called them with the context object the
// join throws `"paths[0]" must be of type string, got object` and the plugin
// "fails to load". Keeping them here (imported by index.ts, never re-exported)
// means the entry module exposes only the real factory. The unit suite imports
// these directly from this module.

import { readFileSync, writeFileSync, copyFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { Plugin } from "@opencode-ai/plugin";

/** The OpenCode client handed to a plugin factory (narrowed to what we log with). */
export type LogClient = Parameters<Plugin>[0]["client"];

export interface PluginConfig {
  name: string;
  version: string;
}

export const CONFIG_FILE = "session-start-config.json";

/** Resolve the directory a compiled module lives in (pass `import.meta.url`). */
export function moduleDir(metaUrl: string): string {
  return dirname(fileURLToPath(metaUrl));
}

/**
 * Read {name, version} from the rendered config JSON. The renderer writes it to
 * `dist/session-start-config.json`; the compiled plugin lives next to that dist
 * dir, so we look in the module dir and its `dist` sibling. Throws if absent —
 * the payload is malformed without it.
 */
export function readConfig(baseDir: string): PluginConfig {
  const candidates = [
    join(baseDir, CONFIG_FILE),
    join(baseDir, "dist", CONFIG_FILE),
  ];
  for (const c of candidates) {
    if (existsSync(c)) {
      const parsed = JSON.parse(readFileSync(c, "utf-8")) as PluginConfig;
      return { name: parsed.name, version: parsed.version };
    }
  }
  throw new Error(`shipsmooth: ${CONFIG_FILE} not found near ${baseDir}`);
}

/**
 * The skill the start command delegates to, and the dir name it is staged under:
 * `shipsmooth-start` (prod) or `shipsmooth-start-dev` (dev).
 *
 * Namespaced (not bare `start`) because OpenCode's skills/ namespace is shared
 * host-wide and flat — a bare `start` would collide with any other `start` skill
 * (first discovered wins + warns). This must match the rendered skill dir basename
 * and its frontmatter `name:` (set in the opencode render spec).
 */
export function skillName(pluginName: string): string {
  return pluginName.endsWith("-dev") ? "shipsmooth-start-dev" : "shipsmooth-start";
}

/** Thin launcher template — points the agent at the canonical skill, no inlined workflow. */
export function startCommandTemplate(pluginName: string): string {
  const skill = skillName(pluginName);
  return (
    `Apply the shipsmooth agent coding workflow. Invoke the \`${skill}\` skill ` +
    `(via the skill tool) and follow it for this task.`
  );
}

/** Command id registered in OpenCode's config.command map. */
export function startCommandId(pluginName: string): string {
  return `${pluginName}:start`;
}

/** Path to the bundled installer the bootstrap shells out to. */
export function installerPath(baseDir: string): string {
  return join(baseDir, "hooks", "install-shipsmooth.sh");
}

/** Best-effort structured log; never throws (logging must not break a session). */
export async function safeLog(
  client: LogClient,
  level: "info" | "error",
  message: string,
): Promise<void> {
  try {
    await client.app.log({ body: { service: "shipsmooth", level, message } });
  } catch {
    // logging is best-effort
  }
}

// ── Skill staging (plan-88) ─────────────────────────────────────────────────
//
// OpenCode discovers SKILL.md ONLY from filesystem dirs it scans (~/.config/
// opencode/skills, <project>/.opencode/skills, ~/.claude/skills, .agents/skills,
// …) — NEVER from inside a node_modules package. So the bundled skills/<name>/
// SKILL.md the plugin ships is invisible until we copy it into a scanned dir.
//
// We stage it ourselves, mirroring how the plugin was installed: a project-scoped
// install (package listed in <worktree>/opencode.json) → <worktree>/.opencode/
// skills/; a global install (listed in <config>/opencode.json) → <config>/skills/;
// and global as the fallback when scope can't be determined. A version marker beside
// the staged skill makes re-staging idempotent across sessions and fresh across
// version bumps (OpenCode has no per-version skill dirs).

/** Marker file (beside the staged SKILL.md) recording the staged plugin version. */
export const SKILL_VERSION_MARKER = ".shipsmooth-version";

/**
 * Resolve the bundled source SKILL.md for `skillName`. In the assembled payload the
 * compiled module lives under `plugin/` but the bundled `skills/` sits at the payload
 * ROOT (next to plugin/, where OpenCode itself discovers a dev filesystem load) — so
 * `<base>/../skills` is the real location. We also accept `<base>/skills` (co-located)
 * for layouts/tests that bundle skills beside the module. First existing wins; if
 * neither exists, return the root candidate (installSkill then reports a clear noop).
 */
export function bundledSkillPath(baseDir: string, skill: string): string {
  const root = join(baseDir, "..", "skills", skill, "SKILL.md");
  const colocated = join(baseDir, "skills", skill, "SKILL.md");
  if (existsSync(root)) return root;
  if (existsSync(colocated)) return colocated;
  return root;
}

/** True if `opencode.json` at `configDir` lists `pkgName` in its `plugin` array. */
function configListsPlugin(configDir: string, pkgName: string): boolean {
  const file = join(configDir, "opencode.json");
  if (!existsSync(file)) return false;
  try {
    const parsed = JSON.parse(readFileSync(file, "utf-8")) as { plugin?: unknown };
    const arr = Array.isArray(parsed.plugin) ? parsed.plugin : [];
    // Entries are either "name" or ["name", options]; match the name in both forms.
    return arr.some((e) => {
      const name = Array.isArray(e) ? e[0] : e;
      return name === pkgName;
    });
  } catch {
    return false; // unreadable/malformed config → treat as "not listed"
  }
}

/**
 * Pick the skills/ destination ROOT mirroring install scope:
 *   - project: `<worktree>/.opencode/skills` when the project opencode.json lists us
 *   - global:  `<configDir>/skills` when the global opencode.json lists us
 *   - fallback: global, when neither lists us (plugins-dir install, unreadable config)
 */
export function resolveSkillsRoot(
  pkgName: string,
  configDir: string,
  worktree: string | undefined,
): string {
  if (worktree && configListsPlugin(worktree, pkgName)) {
    return join(worktree, ".opencode", "skills");
  }
  // global match OR fallback both land in the global config skills dir.
  return join(configDir, "skills");
}

export interface InstallSkillArgs {
  /** Plugin module dir (where bundled skills/ lives) — pass moduleDir(import.meta.url). */
  baseDir: string;
  /** Namespaced skill name/dir, e.g. `shipsmooth-start` — from skillName(cfg.name). */
  skill: string;
  /** Bundled plugin version (cfg.version) — written to the version marker. */
  version: string;
  /** The npm package name as it appears in opencode.json's `plugin` array. */
  pkgName: string;
  /** Authoritative OpenCode config dir (from client.path.get().config). */
  configDir: string;
  /** Project worktree root (PluginInput.worktree), if any. */
  worktree: string | undefined;
}

/** Outcome of installSkill, for logging/testing. */
export type InstallSkillResult =
  | { action: "staged"; dest: string }
  | { action: "skipped"; dest: string }
  | { action: "noop"; reason: string };

/**
 * Stage the bundled SKILL.md into a scope-appropriate, OpenCode-scanned skills/ dir.
 * Idempotent (skips when the marker already records this version) and pure-ish: all
 * effects are filesystem writes the caller has consented to. Returns what it did.
 * Throws only on genuine IO failure — the caller wraps this non-fatally.
 */
export function installSkill(args: InstallSkillArgs): InstallSkillResult {
  const { baseDir, skill, version, pkgName, configDir, worktree } = args;
  const src = bundledSkillPath(baseDir, skill);
  if (!existsSync(src)) {
    return { action: "noop", reason: `bundled skill not found at ${src}` };
  }
  const destDir = join(resolveSkillsRoot(pkgName, configDir, worktree), skill);
  const dest = join(destDir, "SKILL.md");
  const marker = join(destDir, SKILL_VERSION_MARKER);

  // Already staged at this version → nothing to do.
  if (existsSync(dest) && existsSync(marker)) {
    try {
      if (readFileSync(marker, "utf-8").trim() === version) {
        return { action: "skipped", dest };
      }
    } catch {
      // unreadable marker → fall through and re-stage
    }
  }

  mkdirSync(destDir, { recursive: true });
  copyFileSync(src, dest);
  writeFileSync(marker, version);
  return { action: "staged", dest };
}
