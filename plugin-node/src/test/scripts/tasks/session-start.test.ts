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

test('already cached: installRuntime is a no-op', () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '0.2.0';
  const bin = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth-tasks');
  makeExecutable(bin);

  installRuntime({ version, cacheDir, pluginRoot });

  // bin must still exist and no extra files created
  assert.ok(fs.existsSync(bin));
});

test('local runtime: copies from pluginRoot/runtime to cache', () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '0.2.0';
  const srcBin = path.join(pluginRoot, 'runtime', 'bin', 'shipsmooth-tasks');
  makeExecutable(srcBin);

  installRuntime({ version, cacheDir, pluginRoot });

  const destBin = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth-tasks');
  assert.ok(fs.existsSync(destBin), 'binary should be copied to cache');
  const mode = fs.statSync(destBin).mode;
  assert.ok((mode & 0o111) !== 0, 'binary should be executable');
});

test('local runtime: idempotent when called twice', () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '0.2.0';
  makeExecutable(path.join(pluginRoot, 'runtime', 'bin', 'shipsmooth-tasks'));

  installRuntime({ version, cacheDir, pluginRoot });
  installRuntime({ version, cacheDir, pluginRoot });

  const destBin = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth-tasks');
  assert.ok(fs.existsSync(destBin));
});

test('jlinkDir is a non-directory (e.g. /dev/null): does not create runtime dir as a file', () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  // /dev/null exists but is not a directory — must not cpSync it into the runtime dir
  try {
    installRuntime({ version: '0.3.1', cacheDir, pluginRoot, jlinkDir: '/dev/null', forcePlatform: 'linux-x64' });
  } catch {
    // expected: will fail trying to download (no real release at this version in test env)
  }
  const runtimeDir = path.join(cacheDir, 'runtime-0.3.1');
  // must not be a plain file — either absent or a directory
  if (fs.existsSync(runtimeDir)) {
    assert.ok(fs.statSync(runtimeDir).isDirectory(), 'runtime path must be a directory, not a file');
  }
});

test('darwin-x64: installs from jlinkDir without error', () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '0.3.3';
  const jlinkDir = makeTmpDir();
  makeExecutable(path.join(jlinkDir, 'bin', 'shipsmooth-tasks'));

  installRuntime({ version, cacheDir, pluginRoot, jlinkDir, forcePlatform: 'darwin-x64' });

  const destBin = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth-tasks');
  assert.ok(fs.existsSync(destBin), 'darwin-x64 binary should be installed from jlinkDir');
});

test('darwin-arm64: installs from jlinkDir without error', () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '0.3.4';
  const jlinkDir = makeTmpDir();
  makeExecutable(path.join(jlinkDir, 'bin', 'shipsmooth-tasks'));

  installRuntime({ version, cacheDir, pluginRoot, jlinkDir, forcePlatform: 'darwin-arm64' });

  const destBin = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth-tasks');
  assert.ok(fs.existsSync(destBin), 'darwin-arm64 binary should be installed from jlinkDir');
});

test('unsupported platform: throws with clear message', () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();

  assert.throws(
    () => installRuntime({ version: '0.2.0', cacheDir, pluginRoot, forcePlatform: 'win32-x64' }),
    /not yet supported/i,
  );
});