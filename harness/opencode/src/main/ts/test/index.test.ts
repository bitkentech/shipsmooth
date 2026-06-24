import { test } from "node:test";
import assert from "node:assert/strict";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { fileURLToPath } from "node:url";
import { ShipsmoothPlugin } from "../src/index.js";
import {
  readConfig,
  skillName,
  startCommandId,
  startCommandTemplate,
  installerPath,
  moduleDir,
  safeLog,
  installSkill,
  resolveSkillsRoot,
  bundledSkillPath,
  SKILL_VERSION_MARKER,
} from "../src/lib/internal.js";

function tmpDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), "ss-oc-test-"));
}

// ── Pure helpers ───────────────────────────────────────────────────────────

test("skillName: prod name -> shipsmooth-start", () => {
  assert.equal(skillName("shipsmooth"), "shipsmooth-start");
});

test("skillName: dev name -> shipsmooth-start-dev", () => {
  assert.equal(skillName("shipsmooth-dev"), "shipsmooth-start-dev");
});

test("startCommandId: namespaces the command under the plugin name", () => {
  assert.equal(startCommandId("shipsmooth"), "shipsmooth:start");
  assert.equal(startCommandId("shipsmooth-dev"), "shipsmooth-dev:start");
});

test("startCommandTemplate: points at the skill, does not inline workflow", () => {
  const t = startCommandTemplate("shipsmooth");
  assert.match(t, /`shipsmooth-start`/);
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

test("moduleDir: resolves the directory of a file: URL", () => {
  assert.equal(moduleDir("file:///a/b/index.js"), path.join("/a", "b"));
});

test("safeLog: forwards a structured shipsmooth log entry", async () => {
  const entries: any[] = [];
  const client: any = { app: { log: async (e: any) => { entries.push(e); } } };
  await safeLog(client, "info", "hello");
  assert.equal(entries.length, 1);
  assert.deepEqual(entries[0].body, { service: "shipsmooth", level: "info", message: "hello" });
});

test("safeLog: swallows a throwing logger", async () => {
  const client: any = { app: { log: async () => { throw new Error("down"); } } };
  // Must resolve without throwing.
  await safeLog(client, "error", "x");
});

// ── Entry-module export surface (Task 11 regression guard) ─────────────────

// OpenCode 1.17.9 invokes EVERY named export of a plugin file as a plugin
// factory. The entry module must therefore export ONLY the factory (named +
// default) — any extra export (e.g. a path helper) gets called with the plugin
// context and crashes plugin load with `"paths[0]" ... got object`. Pure helpers
// live in ./internal precisely so they are never on this surface.
test("index entry exports only the plugin factory (+ default)", async () => {
  const mod: Record<string, unknown> = await import("../src/index.js");
  const named = Object.keys(mod).filter((k) => k !== "default").sort();
  assert.deepEqual(named, ["ShipsmoothPlugin"],
    `entry module must export only ShipsmoothPlugin; found: ${named.join(", ")}`);
  assert.equal(mod.default, mod.ShipsmoothPlugin, "default export must be the factory");
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

// The factory reads its bundled config/installer/skills relative to its module dir.
// Tests stage those fixtures in a fresh tmp dir per test (never the real src/) and
// hand the factory that dir via the `__baseDir` plugin option. `setBase()` selects
// the dir the writeX helpers target; `baseOpts()` is the option object to pass the
// factory so it reads from the same place.
let pluginModuleDir = "";
function setBase(): string {
  pluginModuleDir = tmpDir();
  return pluginModuleDir;
}
function baseOpts(): { __baseDir: string } {
  return { __baseDir: pluginModuleDir };
}

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
  setBase();
  writeConfig();

  const { ctx } = fakeCtx({});
  const hooks: any = await ShipsmoothPlugin(ctx, baseOpts());
  const cfg: any = {};
  await hooks.config(cfg);

  assert.ok(cfg.command["shipsmooth:start"], "command should be registered");
  assert.match(cfg.command["shipsmooth:start"].template, /`shipsmooth-start`/);
});

test("factory: bootstrap fires once on session.created and runs the installer", async () => {
  setBase();
  writeConfig();
  writeInstaller();

  const { ctx, calls } = fakeCtx({ shellResult: { exitCode: 0, stderr: "" } });
  const hooks: any = await ShipsmoothPlugin(ctx, baseOpts());

  await hooks.event({ event: { type: "session.created" } });
  await hooks.event({ event: { type: "session.created" } }); // second time: idempotent

  assert.equal(calls.shellInvocations, 1, "installer should run exactly once");
});

test("factory: bootstrap is non-fatal when the shell throws", async () => {
  setBase();
  writeConfig();
  writeInstaller();

  const { ctx, calls } = fakeCtx({ shellThrows: true });
  const hooks: any = await ShipsmoothPlugin(ctx, baseOpts());

  // Must not throw out of the event handler.
  await hooks.event({ event: { type: "session.created" } });
  assert.ok(calls.logs.some((l) => /failed/.test(l)), "should log the failure");
});

test("factory: ignores non-session.created events", async () => {
  setBase();
  writeConfig();

  const { ctx, calls } = fakeCtx({});
  const hooks: any = await ShipsmoothPlugin(ctx, baseOpts());
  await hooks.event({ event: { type: "session.updated" } });
  assert.equal(calls.shellInvocations, 0, "no bootstrap for unrelated events");
});

test("factory: returns an inert plugin (no hooks) when config is missing", async () => {
  setBase(); // fresh empty base dir -> no session-start-config.json -> readConfig throws

  const { ctx, calls } = fakeCtx({});
  const hooks: any = await ShipsmoothPlugin(ctx, baseOpts());

  assert.equal(hooks.config, undefined, "inert plugin registers no config hook");
  assert.equal(hooks.event, undefined, "inert plugin registers no event hook");
  assert.ok(calls.logs.some((l) => /not found/.test(l)), "should log the missing config");
});

test("factory: bootstrap logs and no-ops when the installer is missing", async () => {
  setBase();
  writeConfig();
  // No writeInstaller() -> the installer is absent in this fresh base dir.

  const { ctx, calls } = fakeCtx({});
  const hooks: any = await ShipsmoothPlugin(ctx, baseOpts());
  await hooks.event({ event: { type: "session.created" } });

  assert.equal(calls.shellInvocations, 0, "must not run a missing installer");
  assert.ok(calls.logs.some((l) => /installer not found/.test(l)), "should log the missing installer");
});

test("factory: logs a non-zero installer exit code", async () => {
  setBase();
  writeConfig();
  writeInstaller();

  const { ctx, calls } = fakeCtx({ shellResult: { exitCode: 3, stderr: "kaboom" } });
  const hooks: any = await ShipsmoothPlugin(ctx, baseOpts());
  await hooks.event({ event: { type: "session.created" } });

  assert.ok(calls.logs.some((l) => /exit 3/.test(l)), "should log the non-zero exit");
});

test("safeLog: swallows a throwing logger (best-effort)", async () => {
  // config missing -> the inert path calls safeLog; make log throw to hit the catch.
  setBase(); // fresh empty base -> no config -> inert path

  const ctx: any = {
    $: () => ({ nothrow: () => ({ quiet: () => Promise.resolve({}) }) }),
    client: { app: { log: async () => { throw new Error("log down"); } } },
    directory: "/w",
    worktree: "/w",
  };
  // Must not throw even though the logger throws.
  const hooks: any = await ShipsmoothPlugin(ctx, baseOpts());
  assert.deepEqual(hooks, {}, "inert plugin even when logging fails");
});

// ── Integration: the skill becomes discoverable (plan-88 preamble) ─────────
//
// End-to-end behaviour the whole plan exists to deliver: after the plugin's
// session.created bootstrap runs, the bundled SKILL.md is staged into an
// OpenCode-scanned skills/ dir under the namespaced name `shipsmooth-start`,
// so OpenCode's filesystem skill discovery can find it. OpenCode never scans
// node_modules, so without this staging the skill is undetectable.
//
// The plugin learns its destinations from `client.path.get()` (the SDK Path:
// { state, config, worktree, directory }) and from PluginInput.worktree, then
// mirrors install scope: a globally-installed plugin stages into <config>/skills/.
//
// This drives a real on-disk staging through the actual factory + bootstrap.
// It is RED until Task 2 (installSkill) + Task 3 (shipsmooth-start name) land.

/** Stage a fake bundled skill in the plugin's (tmp) module dir so installSkill copies it. */
function writeBundledSkill(skillDirName: string, body: string): void {
  const dir = path.join(pluginModuleDir, "skills", skillDirName);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, "SKILL.md"), body);
}

/** A factory context whose client also answers path.get() with a Path shape. */
function fakeCtxWithPath(paths: { config: string; worktree: string }) {
  const base = fakeCtx({ shellResult: { exitCode: 0, stderr: "" } });
  base.ctx.worktree = paths.worktree;
  base.ctx.directory = paths.worktree;
  base.ctx.client.path = {
    get: async () => ({
      data: { state: paths.config, config: paths.config, worktree: paths.worktree, directory: paths.worktree },
    }),
  };
  return base;
}

test("integration: session.created stages shipsmooth-start into the global config skills dir", async () => {
  // A globally-installed plugin: package listed in <config>/opencode.json only.
  const configDir = tmpDir();
  const worktree = tmpDir();
  fs.writeFileSync(
    path.join(configDir, "opencode.json"),
    JSON.stringify({ plugin: ["@bitkentech/shipsmooth-opencode"] }),
  );

  setBase();
  writeConfig("shipsmooth", "0.3.27");
  writeInstaller();
  writeBundledSkill("shipsmooth-start", "---\nname: shipsmooth-start\n---\n# workflow\n");

  const { ctx } = fakeCtxWithPath({ config: configDir, worktree });
  const hooks: any = await ShipsmoothPlugin(ctx, baseOpts());
  await hooks.event({ event: { type: "session.created" } });

  const staged = path.join(configDir, "skills", "shipsmooth-start", "SKILL.md");
  assert.ok(fs.existsSync(staged), `skill must be staged into a discoverable dir: ${staged}`);
  assert.match(fs.readFileSync(staged, "utf-8"), /name: shipsmooth-start/);
});

// ── installSkill / scope inference (plan-88 Task 2 unit coverage) ───────────

const PKG = "@bitkentech/shipsmooth-opencode";

/** Build a module dir under tmp with a bundled skills/<skill>/SKILL.md. */
function bundleDir(skill: string, body = "---\nname: x\n---\nbody\n"): string {
  const dir = tmpDir();
  const sk = path.join(dir, "skills", skill);
  fs.mkdirSync(sk, { recursive: true });
  fs.writeFileSync(path.join(sk, "SKILL.md"), body);
  return dir;
}

/** Write an opencode.json with the given plugin array into `dir`. */
function writeOpencodeJson(dir: string, plugin: unknown[]): void {
  fs.writeFileSync(path.join(dir, "opencode.json"), JSON.stringify({ plugin }));
}

test("bundledSkillPath: prefers the payload-root skills/ (module under plugin/)", () => {
  // Mirror the assembled payload: <root>/plugin/index.js + <root>/skills/<skill>/SKILL.md.
  const root = tmpDir();
  const base = path.join(root, "plugin");
  fs.mkdirSync(base, { recursive: true });
  const skillDir = path.join(root, "skills", "shipsmooth-start");
  fs.mkdirSync(skillDir, { recursive: true });
  fs.writeFileSync(path.join(skillDir, "SKILL.md"), "x");

  assert.equal(bundledSkillPath(base, "shipsmooth-start"), path.join(skillDir, "SKILL.md"));
});

test("bundledSkillPath: falls back to co-located <base>/skills/ when present", () => {
  const base = tmpDir();
  const skillDir = path.join(base, "skills", "shipsmooth-start");
  fs.mkdirSync(skillDir, { recursive: true });
  fs.writeFileSync(path.join(skillDir, "SKILL.md"), "x");

  assert.equal(bundledSkillPath(base, "shipsmooth-start"), path.join(skillDir, "SKILL.md"));
});

test("bundledSkillPath: neither present -> root candidate (drives a clear noop)", () => {
  assert.equal(bundledSkillPath("/m/plugin", "shipsmooth-start"),
    path.join("/m/plugin", "..", "skills", "shipsmooth-start", "SKILL.md"));
});

test("resolveSkillsRoot: project-listed -> <worktree>/.opencode/skills", () => {
  const worktree = tmpDir();
  writeOpencodeJson(worktree, [PKG]);
  assert.equal(resolveSkillsRoot(PKG, tmpDir(), worktree),
    path.join(worktree, ".opencode", "skills"));
});

test("resolveSkillsRoot: project array uses [name, options] tuple form too", () => {
  const worktree = tmpDir();
  writeOpencodeJson(worktree, [[PKG, { some: "opt" }]]);
  assert.equal(resolveSkillsRoot(PKG, tmpDir(), worktree),
    path.join(worktree, ".opencode", "skills"));
});

test("resolveSkillsRoot: global-listed (not in project) -> <config>/skills", () => {
  const config = tmpDir();
  const worktree = tmpDir();
  writeOpencodeJson(config, [PKG]);   // global lists it
  // worktree has no opencode.json -> not project-scoped
  assert.equal(resolveSkillsRoot(PKG, config, worktree), path.join(config, "skills"));
});

test("resolveSkillsRoot: listed nowhere -> global fallback", () => {
  const config = tmpDir();
  assert.equal(resolveSkillsRoot(PKG, config, tmpDir()), path.join(config, "skills"));
});

test("resolveSkillsRoot: no worktree -> global", () => {
  const config = tmpDir();
  assert.equal(resolveSkillsRoot(PKG, config, undefined), path.join(config, "skills"));
});

test("resolveSkillsRoot: malformed project opencode.json -> global fallback", () => {
  const config = tmpDir();
  const worktree = tmpDir();
  fs.writeFileSync(path.join(worktree, "opencode.json"), "{ not json");
  assert.equal(resolveSkillsRoot(PKG, config, worktree), path.join(config, "skills"));
});

test("installSkill: stages into project scope and writes the version marker", () => {
  const base = bundleDir("shipsmooth-start");
  const config = tmpDir();
  const worktree = tmpDir();
  writeOpencodeJson(worktree, [PKG]);

  const res = installSkill({ baseDir: base, skill: "shipsmooth-start", version: "1.2.3", pkgName: PKG, configDir: config, worktree });

  assert.equal(res.action, "staged");
  const dir = path.join(worktree, ".opencode", "skills", "shipsmooth-start");
  assert.ok(fs.existsSync(path.join(dir, "SKILL.md")));
  assert.equal(fs.readFileSync(path.join(dir, SKILL_VERSION_MARKER), "utf-8"), "1.2.3");
});

test("installSkill: skips when the marker already records this version", () => {
  const base = bundleDir("shipsmooth-start");
  const config = tmpDir();
  const args = { baseDir: base, skill: "shipsmooth-start", version: "1.2.3", pkgName: PKG, configDir: config, worktree: undefined };

  assert.equal(installSkill(args).action, "staged");
  assert.equal(installSkill(args).action, "skipped");   // second call is idempotent
});

test("installSkill: re-stages when the bundled version differs from the marker", () => {
  const base = bundleDir("shipsmooth-start");
  const config = tmpDir();
  const mk = (version: string) =>
    installSkill({ baseDir: base, skill: "shipsmooth-start", version, pkgName: PKG, configDir: config, worktree: undefined });

  assert.equal(mk("1.0.0").action, "staged");
  assert.equal(mk("2.0.0").action, "staged");           // version bump re-stages
  const marker = path.join(config, "skills", "shipsmooth-start", SKILL_VERSION_MARKER);
  assert.equal(fs.readFileSync(marker, "utf-8"), "2.0.0");
});

test("installSkill: re-stages when the marker is missing (unreadable)", () => {
  const base = bundleDir("shipsmooth-start");
  const config = tmpDir();
  const args = { baseDir: base, skill: "shipsmooth-start", version: "1.2.3", pkgName: PKG, configDir: config, worktree: undefined };
  assert.equal(installSkill(args).action, "staged");
  // delete only the marker -> next call must re-stage (dest exists, marker gone)
  fs.rmSync(path.join(config, "skills", "shipsmooth-start", SKILL_VERSION_MARKER));
  assert.equal(installSkill(args).action, "staged");
});

test("installSkill: noop when the bundled source is absent", () => {
  const base = tmpDir(); // no skills/ at all
  const res = installSkill({ baseDir: base, skill: "shipsmooth-start", version: "1", pkgName: PKG, configDir: tmpDir(), worktree: undefined });
  assert.equal(res.action, "noop");
  assert.match((res as any).reason, /not found/);
});

test("factory: skill staging is non-fatal when path.get() yields no config dir", async () => {
  setBase();
  writeConfig("shipsmooth", "0.3.27");
  writeInstaller();

  const { ctx, calls } = fakeCtx({ shellResult: { exitCode: 0, stderr: "" } });
  ctx.client.path = { get: async () => ({ data: {} }) }; // no config field
  const hooks: any = await ShipsmoothPlugin(ctx, baseOpts());
  await hooks.event({ event: { type: "session.created" } }); // must not throw

  assert.ok(calls.logs.some((l) => /no config dir/.test(l)), "should log the missing config dir");
});
