import { test } from "node:test";
import assert from "node:assert/strict";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { fileURLToPath } from "node:url";
import {
  ShipsmoothPlugin,
  readConfig,
  skillName,
  startCommandId,
  startCommandTemplate,
  installerPath,
} from "../src/index.js";

function tmpDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), "ss-oc-test-"));
}

// ── Pure helpers ───────────────────────────────────────────────────────────

test("skillName: prod name -> start", () => {
  assert.equal(skillName("shipsmooth"), "start");
});

test("skillName: dev name -> start-dev", () => {
  assert.equal(skillName("shipsmooth-dev"), "start-dev");
});

test("startCommandId: namespaces the command under the plugin name", () => {
  assert.equal(startCommandId("shipsmooth"), "shipsmooth:start");
  assert.equal(startCommandId("shipsmooth-dev"), "shipsmooth-dev:start");
});

test("startCommandTemplate: points at the skill, does not inline workflow", () => {
  const t = startCommandTemplate("shipsmooth");
  assert.match(t, /`start`/);
  assert.match(t, /skill/i);
  // sanity: it must be a short pointer, not the whole workflow
  assert.ok(t.length < 300, "template should be a thin pointer");
});

test("installerPath: resolves hooks/install-shipsmooth.sh under the base dir", () => {
  assert.equal(installerPath("/x"), path.join("/x", "hooks", "install-shipsmooth.sh"));
});

test("readConfig: reads name+version from the config file", () => {
  const dir = tmpDir();
  fs.writeFileSync(
    path.join(dir, "session-start-config.json"),
    JSON.stringify({ name: "shipsmooth", version: "0.2.0", extra: "ignored" }),
  );
  assert.deepEqual(readConfig(dir), { name: "shipsmooth", version: "0.2.0" });
});

test("readConfig: finds the config in a dist/ subdir too", () => {
  const dir = tmpDir();
  fs.mkdirSync(path.join(dir, "dist"));
  fs.writeFileSync(
    path.join(dir, "dist", "session-start-config.json"),
    JSON.stringify({ name: "shipsmooth-dev", version: "0.9.9" }),
  );
  assert.deepEqual(readConfig(dir), { name: "shipsmooth-dev", version: "0.9.9" });
});

test("readConfig: throws when the config is absent", () => {
  assert.throws(() => readConfig(tmpDir()), /not found/);
});

// ── Factory / hooks ────────────────────────────────────────────────────────

// A minimal fake context. `$` is a tagged-template fn returning a thenable with
// the chainable .nothrow().quiet() used by the bootstrap.
function fakeCtx(opts: { shellResult?: { exitCode: number; stderr: string }; shellThrows?: boolean }) {
  const calls: { logs: string[]; shellInvocations: number } = { logs: [], shellInvocations: 0 };
  const result = opts.shellResult ?? { exitCode: 0, stderr: "" };

  const shellPromise = () => {
    calls.shellInvocations++;
    const chain: any = {
      nothrow: () => chain,
      quiet: () => chain,
      then: (res: (v: any) => void, rej: (e: any) => void) => {
        if (opts.shellThrows) return Promise.resolve().then(() => rej(new Error("boom"))).then(res, rej);
        return Promise.resolve({
          exitCode: result.exitCode,
          stderr: { toString: () => result.stderr },
          stdout: { toString: () => "" },
        }).then(res, rej);
      },
    };
    return chain;
  };

  const ctx: any = {
    $: (..._a: unknown[]) => shellPromise(),
    client: { app: { log: async (e: any) => { calls.logs.push(JSON.stringify(e.body ?? e)); } } },
    directory: "/work",
    worktree: "/work",
  };
  return { ctx, calls };
}

// The factory reads its config relative to its own COMPILED module dir. This test
// is compiled to dist-test/test/, so the plugin module dir is the sibling src/.
const pluginModuleDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "src");

function writeConfig(name = "shipsmooth", version = "0.2.0"): void {
  fs.writeFileSync(
    path.join(pluginModuleDir, "session-start-config.json"),
    JSON.stringify({ name, version }),
  );
}

function writeInstaller(): void {
  fs.mkdirSync(path.join(pluginModuleDir, "hooks"), { recursive: true });
  fs.writeFileSync(path.join(pluginModuleDir, "hooks", "install-shipsmooth.sh"), "#!/bin/sh\n");
}

test("factory: registers the start command with a pointer template", async () => {
  writeConfig();

  const { ctx } = fakeCtx({});
  const hooks: any = await ShipsmoothPlugin(ctx, {});
  const cfg: any = {};
  await hooks.config(cfg);

  assert.ok(cfg.command["shipsmooth:start"], "command should be registered");
  assert.match(cfg.command["shipsmooth:start"].template, /`start`/);
});

test("factory: bootstrap fires once on session.created and runs the installer", async () => {
  writeConfig();
  writeInstaller();

  const { ctx, calls } = fakeCtx({ shellResult: { exitCode: 0, stderr: "" } });
  const hooks: any = await ShipsmoothPlugin(ctx, {});

  await hooks.event({ event: { type: "session.created" } });
  await hooks.event({ event: { type: "session.created" } }); // second time: idempotent

  assert.equal(calls.shellInvocations, 1, "installer should run exactly once");
});

test("factory: bootstrap is non-fatal when the shell throws", async () => {
  writeConfig();
  writeInstaller();

  const { ctx, calls } = fakeCtx({ shellThrows: true });
  const hooks: any = await ShipsmoothPlugin(ctx, {});

  // Must not throw out of the event handler.
  await hooks.event({ event: { type: "session.created" } });
  assert.ok(calls.logs.some((l) => /failed/.test(l)), "should log the failure");
});

test("factory: ignores non-session.created events", async () => {
  writeConfig();

  const { ctx, calls } = fakeCtx({});
  const hooks: any = await ShipsmoothPlugin(ctx, {});
  await hooks.event({ event: { type: "session.updated" } });
  assert.equal(calls.shellInvocations, 0, "no bootstrap for unrelated events");
});

test("factory: returns an inert plugin (no hooks) when config is missing", async () => {
  // Remove the config so readConfig throws.
  const cfg = path.join(pluginModuleDir, "session-start-config.json");
  if (fs.existsSync(cfg)) fs.rmSync(cfg);

  const { ctx, calls } = fakeCtx({});
  const hooks: any = await ShipsmoothPlugin(ctx, {});

  assert.equal(hooks.config, undefined, "inert plugin registers no config hook");
  assert.equal(hooks.event, undefined, "inert plugin registers no event hook");
  assert.ok(calls.logs.some((l) => /not found/.test(l)), "should log the missing config");
});

test("factory: bootstrap logs and no-ops when the installer is missing", async () => {
  writeConfig();
  // Ensure NO installer present.
  const installer = path.join(pluginModuleDir, "hooks", "install-shipsmooth.sh");
  if (fs.existsSync(installer)) fs.rmSync(installer);

  const { ctx, calls } = fakeCtx({});
  const hooks: any = await ShipsmoothPlugin(ctx, {});
  await hooks.event({ event: { type: "session.created" } });

  assert.equal(calls.shellInvocations, 0, "must not run a missing installer");
  assert.ok(calls.logs.some((l) => /installer not found/.test(l)), "should log the missing installer");
});

test("factory: logs a non-zero installer exit code", async () => {
  writeConfig();
  writeInstaller();

  const { ctx, calls } = fakeCtx({ shellResult: { exitCode: 3, stderr: "kaboom" } });
  const hooks: any = await ShipsmoothPlugin(ctx, {});
  await hooks.event({ event: { type: "session.created" } });

  assert.ok(calls.logs.some((l) => /exit 3/.test(l)), "should log the non-zero exit");
});

test("safeLog: swallows a throwing logger (best-effort)", async () => {
  // config missing -> the inert path calls safeLog; make log throw to hit the catch.
  const cfgPath = path.join(pluginModuleDir, "session-start-config.json");
  if (fs.existsSync(cfgPath)) fs.rmSync(cfgPath);

  const ctx: any = {
    $: () => ({ nothrow: () => ({ quiet: () => Promise.resolve({}) }) }),
    client: { app: { log: async () => { throw new Error("log down"); } } },
    directory: "/w",
    worktree: "/w",
  };
  // Must not throw even though the logger throws.
  const hooks: any = await ShipsmoothPlugin(ctx, {});
  assert.deepEqual(hooks, {}, "inert plugin even when logging fails");
});
