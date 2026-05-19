"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.installRuntime = installRuntime;
exports.downloadFile = downloadFile;
exports.resolveCache = resolveCache;
const fs = __importStar(require("node:fs"));
const path = __importStar(require("node:path"));
const os = __importStar(require("node:os"));
const http = __importStar(require("node:http"));
const https = __importStar(require("node:https"));
const promises_1 = require("node:stream/promises");
const adm_zip_bundle_1 = __importDefault(require("./adm-zip-bundle"));
async function installRuntime(opts) {
    const { version, cacheDir, pluginRoot } = opts;
    const runtimeDir = path.join(cacheDir, `runtime-${version}`);
    const bin = path.join(runtimeDir, 'bin', 'shipsmooth-tasks');
    if (isExecutable(bin)) {
        return;
    }
    const platform = opts.forcePlatform ?? detectPlatform();
    const supportedPlatforms = ['linux-x64', 'darwin-x64', 'darwin-arm64'];
    if (!supportedPlatforms.includes(platform)) {
        throw new Error(`shipsmooth: platform ${platform} is not yet supported (supported: ${supportedPlatforms.join(', ')})`);
    }
    const jlinkDir = opts.jlinkDir;
    if (jlinkDir && fs.existsSync(jlinkDir) && fs.statSync(jlinkDir).isDirectory()) {
        fs.cpSync(jlinkDir, runtimeDir, { recursive: true });
        fs.chmodSync(bin, 0o755);
        console.log(`shipsmooth: runtime ${version} installed at ${runtimeDir} from local build`);
    }
    else {
        await downloadAndInstall(version, runtimeDir, platform, opts.releaseUrlBase);
        console.log(`shipsmooth: runtime ${version} installed at ${runtimeDir}`);
    }
}
function isExecutable(p) {
    try {
        fs.accessSync(p, fs.constants.X_OK);
        return true;
    }
    catch {
        return false;
    }
}
function detectPlatform() {
    const platMap = { linux: 'linux', darwin: 'darwin', win32: 'win32' };
    const archMap = { x64: 'x64', arm64: 'arm64' };
    const plat = platMap[process.platform] ?? process.platform;
    const arch = archMap[process.arch] ?? process.arch;
    return `${plat}-${arch}`;
}
async function downloadAndInstall(version, runtimeDir, platform, urlBase) {
    const base = urlBase ?? `https://github.com/bitkentech/shipsmooth/releases/download/v${version}`;
    const url = `${base}/shipsmooth-tasks-${version}-${platform}.zip`;
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'shipsmooth-'));
    const zipFile = path.join(tmp, 'runtime.zip');
    const extractDir = `${runtimeDir}.tmp`;
    try {
        await downloadFile(url, zipFile);
        fs.mkdirSync(extractDir, { recursive: true });
        new adm_zip_bundle_1.default(zipFile).extractAllTo(extractDir, true);
        const extractedBin = path.join(extractDir, 'bin', 'shipsmooth-tasks');
        if (!fs.existsSync(extractedBin)) {
            throw new Error(`shipsmooth: extracted archive is missing bin/shipsmooth-tasks (from ${url})`);
        }
        fs.chmodSync(extractedBin, 0o755);
        const runtimeBinDir = path.join(extractDir, 'runtime', 'bin');
        if (fs.existsSync(runtimeBinDir)) {
            for (const entry of fs.readdirSync(runtimeBinDir)) {
                fs.chmodSync(path.join(runtimeBinDir, entry), 0o755);
            }
        }
        fs.renameSync(extractDir, runtimeDir);
    }
    finally {
        fs.rmSync(tmp, { recursive: true, force: true });
        fs.rmSync(extractDir, { recursive: true, force: true });
    }
}
const MAX_REDIRECTS = 5;
const RETRY_DELAY_MS = 1000;
class HttpStatusError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}
async function downloadFile(url, dest) {
    process.stderr.write(`shipsmooth: downloading runtime from ${url}\n`);
    try {
        await downloadOnce(url, dest);
        return;
    }
    catch (e) {
        if (!shouldRetry(e))
            throw e;
        process.stderr.write(`shipsmooth: download attempt failed (${e.message}); retrying in ${RETRY_DELAY_MS}ms\n`);
    }
    await new Promise((r) => setTimeout(r, RETRY_DELAY_MS));
    await downloadOnce(url, dest);
}
function shouldRetry(e) {
    if (e instanceof HttpStatusError)
        return e.status >= 500;
    return true; // transport errors (ECONNREFUSED, ETIMEDOUT, etc.)
}
async function downloadOnce(url, dest) {
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
        await (0, promises_1.pipeline)(res, out);
        return;
    }
    throw new Error(`shipsmooth: too many redirects fetching ${url}`);
}
function sendGet(url) {
    return new Promise((resolve, reject) => {
        const lib = url.startsWith('https:') ? https : http;
        const req = lib.get(url, { headers: { 'user-agent': 'shipsmooth-runtime-installer' } }, resolve);
        req.on('error', reject);
    });
}
function expandHome(p) {
    return p.startsWith('~/') ? path.join(os.homedir(), p.slice(2)) : p;
}
// If you change this logic, update the cliBin expression in plugin-skill/src/main/jte-src/skills/_partials/base-workflow.jte.md
function resolveCache(config) {
    if (config.cacheDir)
        return expandHome(config.cacheDir);
    const xdgCache = process.env['XDG_CACHE_HOME'] ?? path.join(os.homedir(), '.cache');
    return path.join(xdgCache, 'shipsmooth');
}
// CLI entrypoint — invoked as `node dist/session-start.js` from hooks.json (SessionStart).
if (require.main === module) {
    const configPath = path.join(__dirname, 'session-start-config.json');
    const config = JSON.parse(fs.readFileSync(configPath, 'utf-8'));
    const pluginRoot = process.env['CLAUDE_PLUGIN_ROOT'] ?? '';
    const cacheDir = resolveCache(config);
    installRuntime({ version: config.version, cacheDir, pluginRoot, jlinkDir: config.jlinkDir })
        .catch((e) => {
        process.stderr.write(e.message + '\n');
        process.exit(1);
    });
}
