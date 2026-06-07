import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import * as http from 'node:http';
import { execFileSync } from 'node:child_process';
import AdmZip from 'adm-zip';
import { installRuntime } from '../../../../tasks/session-start';

function makeTmpDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'ss-itest-'));
}

function buildFakeRuntimeZip(): Buffer {
  const zip = new AdmZip();
  const script = '#!/bin/sh\necho fake-runtime "$@"\n';
  zip.addFile('bin/shipsmooth', Buffer.from(script), '', 0o755 << 16);
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
  // Unique version per test run so the cacheDir/runtimeDir paths never collide with
  // a concurrently-running test (node:test runs tests concurrently).
  const version = `9.9.9-cleanup-${process.hrtime.bigint()}`;
  const zipBytes = buildFakeRuntimeZip();
  const server = await startServer(zipBytes);

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

  const bin = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth');
  assert.ok(fs.existsSync(bin), `binary should exist at ${bin}`);
  const mode = fs.statSync(bin).mode;
  assert.ok((mode & 0o111) !== 0, 'binary should be executable');

  const out = execFileSync(bin, ['hello'], { encoding: 'utf8' });
  assert.match(out, /fake-runtime hello/);

  // No leftover .tmp extract dir for THIS install (the download-tmp cleanup invariant,
  // checked on paths this test owns rather than a shared os.tmpdir() snapshot that races
  // against concurrent installs).
  assert.ok(!fs.existsSync(`${path.join(cacheDir, `runtime-${version}`)}.tmp`),
    'extract .tmp dir should be cleaned up');
});

test('integration: installRuntime keeps runtime/bin/* executable from stored zip modes', async () => {
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '9.9.9-perms';

  // runtime/bin/* stored executable in the zip (raw perms) must stay executable —
  // now via keepOriginalPermission, not a bin-only post-extract chmod.
  const zip = new AdmZip();
  zip.addFile('bin/shipsmooth', Buffer.from('#!/bin/sh\necho ok\n'), '', 0o755);
  zip.addFile('runtime/bin/java', Buffer.from('#!/bin/sh\necho fake-java\n'), '', 0o755);
  zip.addFile('runtime/bin/keytool', Buffer.from('#!/bin/sh\necho fake-keytool\n'), '', 0o755);
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

test('integration: installRuntime force-chmods the launcher even if its stored mode lacks +x', async () => {
  // Backstop branch: the one entry point (bin/shipsmooth) is force-chmod'd 0755 after
  // extraction so a producer that ever ships it non-executable still installs runnable.
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '9.9.9-launcher';

  const zip = new AdmZip();
  zip.addFile('bin/shipsmooth', Buffer.from('#!/bin/sh\necho ok\n'), '', 0o644); // NOT executable in the zip
  const zipBytes = zip.toBuffer();

  const server = await startServer(zipBytes);
  try {
    await installRuntime({ version, cacheDir, pluginRoot, forcePlatform: 'linux-x64', releaseUrlBase: server.url } as any);
  } finally {
    await server.close();
  }

  const bin = path.join(cacheDir, `runtime-${version}`, 'bin', 'shipsmooth');
  assert.ok((fs.statSync(bin).mode & 0o111) !== 0, 'launcher must be executable despite non-exec stored mode');
});

test('integration: installRuntime preserves executable bit on runtime/lib/* (jspawnhelper)', async () => {
  // End-to-end regression for the jspawnhelper EACCES bug: a file under runtime/lib/
  // whose zip entry is marked 0755 must end up executable after install. The old
  // extractor dropped unix modes and only re-chmod'd runtime/bin/*, leaving
  // runtime/lib/jspawnhelper at 0666 -> OpenJ9 could not spawn any subprocess.
  const cacheDir = makeTmpDir();
  const pluginRoot = makeTmpDir();
  const version = '9.9.9-jspawn';

  // NB: addFile's attr arg is RAW unix perms (e.g. 0o755), not shifted `<< 16`.
  // The shifted form does not round-trip through AdmZip's own reader (extracts as 0666),
  // which is how the real release zip's modes are recovered on install.
  const zip = new AdmZip();
  zip.addFile('bin/shipsmooth', Buffer.from('#!/bin/sh\necho ok\n'), '', 0o755);
  // Executable helper in runtime/lib/, exactly like the real jlink image's jspawnhelper.
  zip.addFile('runtime/lib/jspawnhelper', Buffer.from('#!/bin/sh\necho fake-helper\n'), '', 0o755);
  // A non-executable sibling in the same dir must stay non-executable (modes honored, not blanket +x).
  zip.addFile('runtime/lib/modules', Buffer.from('not-executable\n'), '', 0o644);
  // A file nested under an auto-created directory — guards the plan caveat that honoring
  // stored modes must not leave any directory non-traversable.
  zip.addFile('runtime/lib/server/classes.jsa', Buffer.from('nested\n'), '', 0o644);
  const zipBytes = zip.toBuffer();

  const server = await startServer(zipBytes);
  try {
    await installRuntime({ version, cacheDir, pluginRoot, forcePlatform: 'linux-x64', releaseUrlBase: server.url } as any);
  } finally {
    await server.close();
  }

  const libDir = path.join(cacheDir, `runtime-${version}`, 'runtime', 'lib');
  const helper = path.join(libDir, 'jspawnhelper');
  assert.ok(fs.existsSync(helper), 'jspawnhelper should exist');
  assert.ok((fs.statSync(helper).mode & 0o111) !== 0, 'runtime/lib/jspawnhelper must be executable');

  const modules = path.join(libDir, 'modules');
  assert.ok((fs.statSync(modules).mode & 0o111) === 0, 'runtime/lib/modules must remain non-executable (modes honored, not blanket +x)');

  // The whole tree must stay traversable: reading a file under an auto-created subdir proves
  // no directory was left without its execute/traverse bit.
  const nested = fs.readFileSync(path.join(libDir, 'server', 'classes.jsa'), 'utf8');
  assert.equal(nested, 'nested\n', 'nested file under an auto-created dir must be readable');
});

test('integration: installRuntime throws if extracted zip is missing bin/shipsmooth', async () => {
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
  assert.match(err!.message, /missing bin\/shipsmooth/);

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