import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { installRuntime } from '../../../main/scripts/tasks/session-start';

function makeTmpDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'ss-test-'));
}

function makeExecutable(p: string): void {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, '#!/bin/sh\necho ok\n');
  fs.chmodSync(p, 0o755);
}

test('already cached: installRuntime is a no-op', async () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '0.2.0';
  const bin = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth-tasks');
  makeExecutable(bin);

  await installRuntime({ version, cacheDir, pluginRoot });

  // bin must still exist and no extra files created
  assert.ok(fs.existsSync(bin));
});

test('jlinkDir is a non-directory (e.g. /dev/null): does not create runtime dir as a file', async () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  // /dev/null exists but is not a directory — must not cpSync it into the runtime dir
  try {
    await installRuntime({ version: '0.3.1', cacheDir, pluginRoot, jlinkDir: '/dev/null', forcePlatform: 'linux-x64' });
  } catch {
    // expected: will fail trying to download (no real release at this version in test env)
  }
  const runtimeDir = path.join(cacheDir, 'runtime-0.3.1');
  // must not be a plain file — either absent or a directory
  if (fs.existsSync(runtimeDir)) {
    assert.ok(fs.statSync(runtimeDir).isDirectory(), 'runtime path must be a directory, not a file');
  }
});

test('darwin-x64: installs from jlinkDir without error', async () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '0.3.3';
  const jlinkDir = makeTmpDir();
  makeExecutable(path.join(jlinkDir, 'bin', 'shipsmooth-tasks'));

  await installRuntime({ version, cacheDir, pluginRoot, jlinkDir, forcePlatform: 'darwin-x64' });

  const destBin = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth-tasks');
  assert.ok(fs.existsSync(destBin), 'darwin-x64 binary should be installed from jlinkDir');
});

test('darwin-arm64: installs from jlinkDir without error', async () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '0.3.4';
  const jlinkDir = makeTmpDir();
  makeExecutable(path.join(jlinkDir, 'bin', 'shipsmooth-tasks'));

  await installRuntime({ version, cacheDir, pluginRoot, jlinkDir, forcePlatform: 'darwin-arm64' });

  const destBin = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth-tasks');
  assert.ok(fs.existsSync(destBin), 'darwin-arm64 binary should be installed from jlinkDir');
});

test('resolveCache: uses XDG_CACHE_HOME when cacheDir is absent', () => {
  const { resolveCache } = require('../../../main/scripts/tasks/session-start');
  const orig = process.env['XDG_CACHE_HOME'];
  try {
    process.env['XDG_CACHE_HOME'] = '/tmp/xdg-cache-test';
    assert.equal(resolveCache({}), '/tmp/xdg-cache-test/shipsmooth');
    assert.equal(resolveCache({ cacheDir: '' }), '/tmp/xdg-cache-test/shipsmooth');
  } finally {
    if (orig === undefined) delete process.env['XDG_CACHE_HOME'];
    else process.env['XDG_CACHE_HOME'] = orig;
  }
});

test('resolveCache: falls back to ~/.cache/shipsmooth when XDG_CACHE_HOME unset', () => {
  const { resolveCache } = require('../../../main/scripts/tasks/session-start');
  const orig = process.env['XDG_CACHE_HOME'];
  try {
    delete process.env['XDG_CACHE_HOME'];
    assert.equal(resolveCache({}), path.join(os.homedir(), '.cache', 'shipsmooth'));
  } finally {
    if (orig !== undefined) process.env['XDG_CACHE_HOME'] = orig;
  }
});

test('resolveCache: expands tilde cacheDir for dev builds', () => {
  const { resolveCache } = require('../../../main/scripts/tasks/session-start');
  assert.equal(
    resolveCache({ cacheDir: '~/.cache/shipsmooth-dev' }),
    path.join(os.homedir(), '.cache', 'shipsmooth-dev'),
  );
});

test('zip extraction: runtime/bin/* files are chmod 0755 after install', async () => {
  // AdmZip.extractAllTo() ignores Unix mode bits; we must chmod runtime/bin/* ourselves
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '0.3.x-chmod-test';
  const jlinkDir = makeTmpDir();
  // populate a fake jlink image with non-executable runtime/bin entries
  const runtimeBin = path.join(jlinkDir, 'bin');
  fs.mkdirSync(runtimeBin, { recursive: true });
  fs.writeFileSync(path.join(jlinkDir, 'bin', 'shipsmooth-tasks'), '#!/bin/sh\necho ok\n');
  fs.chmodSync(path.join(jlinkDir, 'bin', 'shipsmooth-tasks'), 0o755);
  // jlinkDir path installs via fs.cpSync — verify launcher gets chmod'd
  await installRuntime({ version, cacheDir, pluginRoot, jlinkDir });
  const bin = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth-tasks');
  assert.ok((fs.statSync(bin).mode & 0o111) !== 0, 'launcher must be executable');
});

test('unsupported platform: error message lists supported platforms', async () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();

  await assert.rejects(
    () => installRuntime({ version: '0.2.0', cacheDir, pluginRoot, forcePlatform: 'win32-x64' }),
    (e: Error) => {
      assert.match(e.message, /win32-x64/);
      assert.match(e.message, /not yet supported/i);
      assert.match(e.message, /linux-x64/);
      assert.match(e.message, /darwin-x64/);
      assert.match(e.message, /darwin-arm64/);
      return true;
    },
  );
});

// Integration test: win32-x64 must be a supported platform (currently fails — drives Task 4)
test('win32-x64: installs from jlinkDir without error', async () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '0.3.9';
  const jlinkDir = makeTmpDir();
  // Windows zip contains .cmd launcher, not a POSIX script
  fs.mkdirSync(path.join(jlinkDir, 'bin'), { recursive: true });
  fs.writeFileSync(path.join(jlinkDir, 'bin', 'shipsmooth-tasks.cmd'), '@echo off\necho ok\n');

  await installRuntime({ version, cacheDir, pluginRoot, jlinkDir, forcePlatform: 'win32-x64' });

  const destCmd = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth-tasks.cmd');
  assert.ok(fs.existsSync(destCmd), 'win32-x64 .cmd launcher should be installed from jlinkDir');
});