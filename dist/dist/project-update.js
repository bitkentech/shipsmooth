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
exports.projectUpdate = projectUpdate;
const fs = __importStar(require("node:fs"));
const path = __importStar(require("node:path"));
const xml_utils_1 = require("./xml-utils");
function projectUpdate(xml, message, blocked, timestamp, newStatus) {
    const plan = (0, xml_utils_1.parsePlanXml)(xml);
    plan.projectUpdates.push({ timestamp, blocked, message });
    if (newStatus) {
        plan.metadata.status = newStatus;
    }
    return (0, xml_utils_1.serializePlanXml)(plan);
}
// CLI entrypoint
if (require.main === module) {
    const args = process.argv.slice(2);
    const planIdx = args.indexOf('--plan');
    const msgIdx = args.indexOf('--message');
    const statusIdx = args.indexOf('--status');
    const blockedFlag = args.includes('--blocked');
    if (planIdx === -1 || msgIdx === -1) {
        console.error('Usage: node project-update.js --plan <N> --message <text> [--blocked] [--status <status>]');
        process.exit(1);
    }
    const planNum = parseInt(args[planIdx + 1], 10);
    const message = args[msgIdx + 1];
    const newStatus = statusIdx !== -1 ? args[statusIdx + 1] : undefined;
    const timestamp = new Date().toISOString();
    const xmlPath = path.join('.agents', 'plans', `plan-${planNum}-tasks.xml`);
    const xml = fs.readFileSync(xmlPath, 'utf-8');
    const updated = projectUpdate(xml, message, blockedFlag, timestamp, newStatus);
    fs.writeFileSync(xmlPath, updated, 'utf-8');
    console.log(`Project update added${newStatus ? ` (status → ${newStatus})` : ''}`);
}
