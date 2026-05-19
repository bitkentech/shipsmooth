import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import * as http from 'node:http';
import { execFileSync } from 'node:child_process';
import AdmZip from 'adm-zip';
import { installRuntime } from '../../../main/scripts/tasks/session-start';

function makeTmpDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'ss-itest-'));
}

function buildFakeRuntimeZip(): Buffer {
  const zip = new AdmZip();
  const script = '#!/bin/sh\necho fake-runtime "$@"\n';
  zip.addFile('bin/shipsmooth-tasks', Buffer.from(script), '', 0o755 << 16);
  zip.addFile('lib/dummy.txt', Buffer.from('dummy\n'));
  zip.addFile('conf/empty.conf', Buffer.from(''));
  return zip.toBuffer();
}

function startServer(zipBytes: Buffer): Promise<{ url: string; close: () => Promise<void> }> {
  return new Promise((resolve) => {
    const server = http.createServer((_req, res) => {
      res.writeHead(200, { 'content-type': 'application/zip', 'content-length': String(zipBytes.length) });
      res.end(zipBytes);
    });
    server.listen(0, '127.0.0.1', () => {
      const addr = server.address();
      if (typeof addr === 'string' || !addr) throw new Error('bad address');
      resolve({
        url: `http://127.0.0.1:${addr.port}`,
        close: () => new Promise((r) => server.close(() => r())),
      });
    });
  });
}

test('integration: installRuntime downloads from a URL override, extracts, chmods, and cleans up tmp', async () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '9.9.9-test';
  const zipBytes = buildFakeRuntimeZip();
  const server = await startServer(zipBytes);

  const tmpEntriesBefore = fs.readdirSync(os.tmpdir()).filter((n) => n.startsWith('shipsmooth-'));

  try {
    await installRuntime({
      version,
      cacheDir,
      pluginRoot,
      forcePlatform: 'linux-x64',
      releaseUrlBase: server.url,
    } as any);
  } finally {
    await server.close();
  }

  const bin = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth-tasks');
  assert.ok(fs.existsSync(bin), `binary should exist at ${bin}`);
  const mode = fs.statSync(bin).mode;
  assert.ok((mode & 0o111) !== 0, 'binary should be executable');

  const out = execFileSync(bin, ['hello'], { encoding: 'utf8' });
  assert.match(out, /fake-runtime hello/);

  // No new shipsmooth-* dirs in os.tmpdir — finally{} cleaned the download tmp
  const tmpEntriesAfter = fs.readdirSync(os.tmpdir()).filter((n) => n.startsWith('shipsmooth-'));
  assert.deepEqual(tmpEntriesAfter, tmpEntriesBefore, 'no shipsmooth-* tmp dirs should be left behind');

  // No leftover .tmp extract dir in cacheDir
  assert.ok(!fs.existsSync(`${path.join(cacheDir, `runtime-${version}`)}.tmp`),
    'extract .tmp dir should be cleaned up');
});

test('integration: installRuntime chmods runtime/bin/* to executable after extraction', async () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '9.9.9-perms';

  // Build a zip where runtime/bin/java has no executable bit stored
  const zip = new AdmZip();
  zip.addFile('bin/shipsmooth-tasks', Buffer.from('#!/bin/sh\necho ok\n'), '', 0o755 << 16);
  zip.addFile('runtime/bin/java', Buffer.from('#!/bin/sh\necho fake-java\n'), '', 0o644 << 16);
  zip.addFile('runtime/bin/keytool', Buffer.from('#!/bin/sh\necho fake-keytool\n'), '', 0o644 << 16);
  const zipBytes = zip.toBuffer();

  const server = await startServer(zipBytes);
  try {
    await installRuntime({ version, cacheDir, pluginRoot, forcePlatform: 'linux-x64', releaseUrlBase: server.url } as any);
  } finally {
    await server.close();
  }

  const runtimeBin = path.join(cacheDir, `runtime-${version}`, 'runtime', 'bin');
  for (const name of ['java', 'keytool']) {
    const f = path.join(runtimeBin, name);
    assert.ok(fs.existsSync(f), `${name} should exist`);
    assert.ok((fs.statSync(f).mode & 0o111) !== 0, `${name} should be executable`);
  }
});

test('integration: installRuntime throws if extracted zip is missing bin/shipsmooth-tasks', async () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '9.9.9-test';

  const malformed = new AdmZip();
  malformed.addFile('lib/only.txt', Buffer.from('no bin in here\n'));
  const zipBytes = malformed.toBuffer();

  const server = await startServer(zipBytes);
  let err: Error | undefined;
  try {
    await installRuntime({
      version,
      cacheDir,
      pluginRoot,
      forcePlatform: 'linux-x64',
      releaseUrlBase: server.url,
    } as any);
  } catch (e: any) {
    err = e;
  } finally {
    await server.close();
  }

  assert.ok(err, 'expected throw on malformed archive');
  assert.match(err!.message, /missing bin\/shipsmooth-tasks/);

  // Must NOT leave a partial runtimeDir behind
  const runtimeDir = path.join(cacheDir, `runtime-${version}`);
  assert.ok(!fs.existsSync(runtimeDir), 'partial runtime directory should be cleaned up');
});

test('integration: installRuntime surfaces the URL in error when download fails', async () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const server = await new Promise<{ url: string; close: () => Promise<void> }>((resolve) => {
    const s = http.createServer((_req, res) => { res.writeHead(404); res.end(); });
    s.listen(0, '127.0.0.1', () => {
      const addr = s.address();
      if (typeof addr === 'string' || !addr) throw new Error('bad address');
      resolve({ url: `http://127.0.0.1:${addr.port}`, close: () => new Promise((r) => s.close(() => r())) });
    });
  });

  let err: Error | undefined;
  try {
    await installRuntime({
      version: '9.9.9-test',
      cacheDir,
      pluginRoot,
      forcePlatform: 'linux-x64',
      releaseUrlBase: server.url,
    } as any);
  } catch (e: any) {
    err = e;
  } finally {
    await server.close();
  }

  assert.ok(err, 'expected installRuntime to throw on 404');
  assert.match(err!.message, /127\.0\.0\.1/, 'error message should include the failing URL host');
});