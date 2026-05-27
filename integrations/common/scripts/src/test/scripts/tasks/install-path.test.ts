import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, accessSync, constants } from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';

const REPO_ROOT = path.resolve(__dirname, '../../../../..');

test('Integration: dev build populates build/runtime/bin/shipsmooth-tasks', () => {
  const bin = path.join(REPO_ROOT, 'build', 'runtime', 'bin', 'shipsmooth-tasks');
  assert.ok(existsSync(bin), `Expected ${bin} to exist after mvn process-resources`);
  assert.doesNotThrow(
    () => accessSync(bin, constants.X_OK),
    `Expected ${bin} to be executable`
  );
});

test('Integration: dev runtime responds to --help', () => {
  const bin = path.join(REPO_ROOT, 'build', 'runtime', 'bin', 'shipsmooth-tasks');
  let output: string;
  try {
    output = execFileSync(bin, ['--help'], { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (e: any) {
    output = (e.stdout || '') + (e.stderr || '');
  }
  assert.ok(output.length > 0, 'Expected --help to produce output');
});
