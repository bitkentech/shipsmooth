// shipsmooth OpenCode plugin (plan-86 Task 10).
//
// The only executable code shipsmooth ships to any host. Two jobs, both proven
// in the Task 1 de-risk:
//   1. Bootstrap the jlink runtime on the first `session.created` event by
//      shelling out (via Bun's `$`) to the bundled hooks/install-shipsmooth.sh.
//      Idempotent + non-fatal: a failure logs and degrades to "CLI unavailable",
//      never crashing the session.
//   2. Register the `shipsmooth:start` slash command whose template is a thin
//      pointer to the native `start` SKILL.md (the single-sourced workflow text).
//
// Authored in TS, shipped as plain transpiled .js (Task 4). `@opencode-ai/plugin`
// is a type-only devDependency.
//
// The version + skill name come from the rendered config JSON next to the
// compiled module (dist/session-start-config.json), so a version bump re-renders
// one file and this source is untouched.

import { readFileSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { Plugin, Hooks } from "@opencode-ai/plugin";

interface PluginConfig {
  name: string;
  version: string;
}

const CONFIG_FILE = "session-start-config.json";

/** Resolve the directory the compiled plugin module lives in. */
function moduleDir(): string {
  return dirname(fileURLToPath(import.meta.url));
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

type LogClient = Parameters<Plugin>[0]["client"];

export const ShipsmoothPlugin: Plugin = async ({ client, $ }) => {
  const base = moduleDir();
  let cfg: PluginConfig;
  try {
    cfg = readConfig(base);
  } catch (e) {
    // Without config we cannot bootstrap; log and return an inert plugin.
    await safeLog(client, "error", `shipsmooth: ${(e as Error).message}`);
    return {};
  }

  let bootstrapped = false;
  async function bootstrap(): Promise<void> {
    if (bootstrapped) return;
    bootstrapped = true;
    const installer = installerPath(base);
    if (!existsSync(installer)) {
      await safeLog(client, "error", `shipsmooth: installer not found at ${installer}`);
      return;
    }
    try {
      // Idempotent: the script early-exits if the runtime is already installed.
      const res = await $`sh ${installer} ${cfg.name} ${cfg.version}`.nothrow().quiet();
      if (res.exitCode !== 0) {
        await safeLog(client, "error",
          `shipsmooth: runtime bootstrap exit ${res.exitCode}: ${res.stderr.toString().trim()}`);
      } else {
        await safeLog(client, "info", `shipsmooth: runtime ${cfg.version} ready`);
      }
    } catch (e) {
      // Non-fatal: a missing runtime degrades to "CLI unavailable", never crashes.
      await safeLog(client, "error", `shipsmooth: bootstrap failed: ${(e as Error).message}`);
    }
  }

  const hooks: Hooks = {
    config: async (config) => {
      config.command = config.command ?? {};
      config.command[startCommandId(cfg.name)] = {
        description: "Apply the shipsmooth agent coding workflow",
        template: startCommandTemplate(cfg.name),
      };
    },
    event: async ({ event }) => {
      if (event?.type === "session.created") {
        await bootstrap();
      }
    },
  };
  return hooks;
};

async function safeLog(
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

export default ShipsmoothPlugin;
