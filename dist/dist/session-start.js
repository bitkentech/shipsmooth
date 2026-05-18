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
Object.defineProperty(exports, "__esModule", { value: true });
exports.installRuntime = installRuntime;
const fs = __importStar(require("node:fs"));
const path = __importStar(require("node:path"));
const child_process = __importStar(require("node:child_process"));
const os = __importStar(require("node:os"));
function installRuntime(opts) {
    const { version, cacheDir, pluginRoot } = opts;
    const runtimeDir = path.join(cacheDir, `runtime-${version}`);
    const bin = path.join(runtimeDir, 'bin', 'shipsmooth-tasks');
    if (isExecutable(bin)) {
        return;
    }
    const platform = opts.forcePlatform ?? detectPlatform();
    if (platform !== 'linux-x64') {
        throw new Error(`shipsmooth: platform ${platform} is not yet supported`);
    }
    const jlinkDir = opts.jlinkDir;
    if (jlinkDir && fs.existsSync(jlinkDir) && fs.statSync(jlinkDir).isDirectory()) {
        fs.cpSync(jlinkDir, runtimeDir, { recursive: true });
        fs.chmodSync(bin, 0o755);
        console.log(`shipsmooth: runtime ${version} installed at ${runtimeDir} from local build`);
    }
    else {
        downloadAndInstall(version, runtimeDir);
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
function downloadAndInstall(version, runtimeDir) {
    const url = `https://github.com/bitkentech/shipsmooth/releases/download/v${version}/shipsmooth-tasks-${version}-linux-x64.zip`;
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'shipsmooth-'));
    const zipFile = path.join(tmp, 'runtime.zip');
    const extractDir = `${runtimeDir}.tmp`;
    try {
        downloadFile(url, zipFile);
        fs.mkdirSync(extractDir, { recursive: true });
        child_process.execFileSync('unzip', ['-q', zipFile, '-d', extractDir]);
        fs.renameSync(path.join(extractDir, `shipsmooth-tasks-${version}`), runtimeDir);
    }
    finally {
        fs.rmSync(tmp, { recursive: true, force: true });
        fs.rmSync(extractDir, { recursive: true, force: true });
    }
}
function downloadFile(url, dest) {
    const result = child_process.spawnSync('curl', ['-fsSL', url, '-o', dest], { encoding: 'utf8' });
    if (result.status !== 0) {
        throw new Error(`shipsmooth: failed to download runtime: ${result.stderr}`);
    }
}
function expandHome(p) {
    return p.startsWith('~/') ? path.join(os.homedir(), p.slice(2)) : p;
}
// CLI entrypoint — invoked by the hooks.json node -e bootstrap
if (require.main === module) {
    const configPath = path.join(__dirname, 'session-start-config.json');
    const config = JSON.parse(fs.readFileSync(configPath, 'utf-8'));
    const pluginRoot = process.env['CLAUDE_PLUGIN_ROOT'] ?? '';
    const cacheDir = expandHome(config.cacheDir);
    try {
        installRuntime({ version: config.version, cacheDir, pluginRoot, jlinkDir: config.jlinkDir });
    }
    catch (e) {
        process.stderr.write(e.message + '\n');
        process.exit(1);
    }
}
