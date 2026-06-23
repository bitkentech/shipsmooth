// shipsmooth OpenCode plugin entry (plan-86 Task 10/11).
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
// IMPORTANT (Task 11 integration finding). OpenCode 1.17.9 discovers plugins by
// scanning `<config-dir>/plugin/*.js` (NON-recursively) and, for each file, calls
// EVERY export as a plugin factory (`for (const v of Object.values(mod))`). Two
// consequences this module is shaped around:
//   1. The entry exports ONLY the factory (named + default). Exporting a helper
//      here would make OpenCode call e.g. readConfig(context) → it path.joins the
//      context object → plugin load crashes with `"paths[0]" ... got object`.
//   2. The pure helpers live in ./lib/internal.js — under plugin/lib/, NOT
//      plugin/, so OpenCode's non-recursive scan never loads them as a plugin (a
//      sibling plugin/internal.js WOULD be loaded and rejected as
//      "Plugin export is not a function" for its non-function exports).
//
// The version + skill name come from the rendered config JSON next to the
// compiled module (dist/session-start-config.json), so a version bump re-renders
// one file and this source is untouched.

import { existsSync } from "node:fs";
import type { Plugin, Hooks } from "@opencode-ai/plugin";
import {
  type PluginConfig,
  moduleDir,
  readConfig,
  startCommandId,
  startCommandTemplate,
  installerPath,
  safeLog,
} from "./lib/internal.js";

export const ShipsmoothPlugin: Plugin = async ({ client, $ }) => {
  const base = moduleDir(import.meta.url);
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

export default ShipsmoothPlugin;
