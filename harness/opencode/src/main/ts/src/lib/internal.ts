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

import { readFileSync, existsSync } from "node:fs";
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

/** The skill the start command delegates to: `start` (prod) or `start-dev` (dev). */
export function skillName(pluginName: string): string {
  return pluginName.endsWith("-dev") ? "start-dev" : "start";
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
