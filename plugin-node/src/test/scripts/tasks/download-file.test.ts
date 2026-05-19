import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import * as http from 'node:http';
import { downloadFile } from '../../../main/scripts/tasks/session-start';

function makeTmpDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'dl-test-'));
}

function startServer(handler: http.RequestListener): Promise<{ url: string; close: () => Promise<void> }> {
  return new Promise((resolve) => {
    const server = http.createServer(handler);
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

test('downloadFile: downloads body to destination over plain http', async () => {
  const payload = Buffer.from('hello-runtime-bytes');
  const server = await startServer((_req, res) => {
    res.writeHead(200, { 'content-length': String(payload.length) });
    res.end(payload);
  });
  const dest = path.join(makeTmpDir(), 'out.bin');

  try {
    await downloadFile(`${server.url}/file.zip`, dest);
  } finally {
    await server.close();
  }

  assert.deepEqual(fs.readFileSync(dest), payload);
});

test('downloadFile: follows 302 redirects to the final body', async () => {
  const payload = Buffer.from('redirected-body');
  let secondaryUrl = '';

  const secondary = await startServer((_req, res) => {
    res.writeHead(200);
    res.end(payload);
  });
  secondaryUrl = `${secondary.url}/final.zip`;

  const primary = await startServer((_req, res) => {
    res.writeHead(302, { location: secondaryUrl });
    res.end();
  });

  const dest = path.join(makeTmpDir(), 'redirect.bin');
  try {
    await downloadFile(`${primary.url}/start.zip`, dest);
  } finally {
    await primary.close();
    await secondary.close();
  }

  assert.deepEqual(fs.readFileSync(dest), payload);
});

test('downloadFile: throws with URL embedded on non-2xx final response', async () => {
  const server = await startServer((_req, res) => {
    res.writeHead(404);
    res.end();
  });
  const dest = path.join(makeTmpDir(), 'should-not-exist.bin');
  const url = `${server.url}/missing.zip`;

  let err: Error | undefined;
  try {
    await downloadFile(url, dest);
  } catch (e: any) {
    err = e;
  } finally {
    await server.close();
  }

  assert.ok(err, 'expected throw on 404');
  assert.match(err!.message, new RegExp(server.url.replace(/[.]/g, '\\.')));
  assert.match(err!.message, /404/);
});

test('downloadFile: aborts after too many redirect hops', async () => {
  // Loop a server to itself, count hops via header.
  const server = await startServer((req, res) => {
    const hops = Number(req.headers['x-test-hops'] ?? '0') + 1;
    // unconditionally redirect to self
    res.writeHead(302, { location: `http://127.0.0.1:${(server as any)._port}/` });
    res.end();
  });
  // hack: stash port for handler closure
  (server as any)._port = new URL(server.url).port;

  const dest = path.join(makeTmpDir(), 'loop.bin');
  let err: Error | undefined;
  try {
    await downloadFile(server.url, dest);
  } catch (e: any) {
    err = e;
  } finally {
    await server.close();
  }
  assert.ok(err, 'expected throw on redirect loop');
  assert.match(err!.message, /redirect/i);
});
