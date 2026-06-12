import * as fs from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import * as http from 'node:http';
import * as https from 'node:https';
import { pipeline } from 'node:stream/promises';
import AdmZip from './adm-zip-bundle';

export interface InstallOptions {
  version: string;
  cacheDir: string;
  pluginRoot: string;
  jlinkDir?: string;       // dev builds only: path to target/jlink-image
  forcePlatform?: string;  // for testing; overrides actual platform detection
  releaseUrlBase?: string; // for testing; overrides https://github.com/.../releases/download/v{version}
}

export function runtimeBin(runtimeDir: string, platform: string): string {
  return path.join(runtimeDir, 'bin',
    platform.startsWith('win32') ? 'shipsmooth.cmd' : 'shipsmooth');
}

export async function installRuntime(opts: InstallOptions): Promise<void> {
  const { version, cacheDir, pluginRoot } = opts;
  const runtimeDir = path.join(cacheDir, version);
  const platform = opts.forcePlatform ?? detectPlatform();
  const bin = runtimeBin(runtimeDir, platform);

  if (isExecutable(bin, platform)) {
    return;
  }

  const supportedPlatforms = ['linux-x64', 'darwin-x64', 'darwin-arm64', 'win32-x64'];
  if (!supportedPlatforms.includes(platform)) {
    throw new Error(
      `shipsmooth: platform ${platform} is not yet supported (supported: ${supportedPlatforms.join(', ')})`,
    );
  }

  const jlinkDir = opts.jlinkDir;
  if (jlinkDir && fs.existsSync(jlinkDir) && fs.statSync(jlinkDir).isDirectory()) {
    fs.cpSync(jlinkDir, runtimeDir, { recursive: true });
    if (!platform.startsWith('win32')) {
      fs.chmodSync(bin, 0o755);
    }
    console.log(`shipsmooth: runtime ${version} installed at ${runtimeDir} from local build`);
  } else {
    await downloadAndInstall(version, runtimeDir, platform, opts.releaseUrlBase);
    console.log(`shipsmooth: runtime ${version} installed at ${runtimeDir}`);
  }
}

function isExecutable(p: string, platform?: string): boolean {
  if (platform?.startsWith('win32') || process.platform === 'win32') {
    return fs.existsSync(p);
  }
  try {
    fs.accessSync(p, fs.constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function detectPlatform(): string {
  const platMap: Record<string, string> = { linux: 'linux', darwin: 'darwin', win32: 'win32' };
  const archMap: Record<string, string> = { x64: 'x64', arm64: 'arm64' };
  const plat = platMap[process.platform] ?? process.platform;
  const arch = archMap[process.arch] ?? process.arch;
  return `${plat}-${arch}`;
}

async function downloadAndInstall(version: string, runtimeDir: string, platform: string, urlBase?: string): Promise<void> {
  const base = urlBase ?? `https://github.com/bitkentech/shipsmooth/releases/download/v${version}`;
  const url = `${base}/shipsmooth-${version}-${platform}.zip`;
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'shipsmooth-'));
  const zipFile = path.join(tmp, 'runtime.zip');
  const extractDir = `${runtimeDir}.tmp`;
  const isWin = platform.startsWith('win32');

  try {
    await downloadFile(url, zipFile);
    fs.mkdirSync(extractDir, { recursive: true });
    // keepOriginalPermission=true preserves the unix mode stored in each zip entry
    // (notably the +x on runtime/lib/jspawnhelper, which OpenJ9 needs to spawn subprocesses).
    new AdmZip(zipFile).extractAllTo(extractDir, true, true);
    const extractedBin = runtimeBin(extractDir, platform);
    if (!fs.existsSync(extractedBin)) {
      throw new Error(`shipsmooth: extracted archive is missing ${path.relative(extractDir, extractedBin)} (from ${url})`);
    }
    // Executable bits across the tree (runtime/bin/*, runtime/lib/jspawnhelper, ...) are
    // restored by keepOriginalPermission above. We still force the top-level launcher
    // executable as a backstop: it is the one entry point, and a producer that ever forgets
    // its mode would otherwise leave the install unrunnable.
    if (!isWin) {
      fs.chmodSync(extractedBin, 0o755);
    }
    fs.renameSync(extractDir, runtimeDir);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
    fs.rmSync(extractDir, { recursive: true, force: true });
  }
}

const MAX_REDIRECTS = 5;
const RETRY_DELAY_MS = 1000;

class HttpStatusError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function downloadFile(url: string, dest: string): Promise<void> {
  process.stderr.write(`shipsmooth: downloading runtime from ${url}\n`);
  try {
    await downloadOnce(url, dest);
    return;
  } catch (e: any) {
    if (!shouldRetry(e)) throw e;
    process.stderr.write(`shipsmooth: download attempt failed (${e.message}); retrying in ${RETRY_DELAY_MS}ms\n`);
  }
  await new Promise((r) => setTimeout(r, RETRY_DELAY_MS));
  await downloadOnce(url, dest);
}

function shouldRetry(e: unknown): boolean {
  if (e instanceof HttpStatusError) return e.status >= 500;
  return true; // transport errors (ECONNREFUSED, ETIMEDOUT, etc.)
}

async function downloadOnce(url: string, dest: string): Promise<void> {
  let currentUrl = url;
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    const res = await sendGet(currentUrl);
    const status = res.statusCode ?? 0;

    if (status >= 300 && status < 400 && res.headers.location) {
      res.resume();
      currentUrl = new URL(res.headers.location, currentUrl).toString();
      continue;
    }

    if (status < 200 || status >= 300) {
      res.resume();
      throw new HttpStatusError(status, `shipsmooth: failed to download ${currentUrl}: HTTP ${status}`);
    }

    const out = fs.createWriteStream(dest);
    await pipeline(res, out);
    return;
  }
  throw new Error(`shipsmooth: too many redirects fetching ${url}`);
}

function sendGet(url: string): Promise<http.IncomingMessage> {
  return new Promise((resolve, reject) => {
    const lib = url.startsWith('https:') ? https : http;
    const req = lib.get(url, { headers: { 'user-agent': 'shipsmooth-runtime-installer' } }, resolve);
    req.on('error', reject);
  });
}

// If you change this logic, update the cliBin expression in plugin-skill/src/main/jte-src/skills/_partials/base-workflow.jte.md
export function resolveCache(config: { name?: string }): string {
  const subdir = config.name ?? 'shipsmooth';
  if (process.platform === 'win32') {
    const localAppData = process.env['LOCALAPPDATA'] ?? path.join(os.homedir(), 'AppData', 'Local');
    return path.join(localAppData, subdir);
  }
  const xdgCache = process.env['XDG_CACHE_HOME'] ?? path.join(os.homedir(), '.cache');
  return path.join(xdgCache, subdir);
}

// CLI entrypoint — invoked as `node dist/session-start.js` from hooks.json (SessionStart).
if (require.main === module) {
  const configPath = path.join(__dirname, 'session-start-config.json');
  const config = JSON.parse(fs.readFileSync(configPath, 'utf-8'));
  const pluginRoot = process.env['CLAUDE_PLUGIN_ROOT'] ?? '';
  const cacheDir = resolveCache(config);

  installRuntime({ version: config.version, cacheDir, pluginRoot, jlinkDir: config.jlinkDir })
    .catch((e: any) => {
      process.stderr.write(e.message + '\n');
      process.exit(1);
    });
}