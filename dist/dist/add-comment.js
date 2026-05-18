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
exports.addComment = addComment;
const fs = __importStar(require("node:fs"));
const path = __importStar(require("node:path"));
const xml_utils_1 = require("./xml-utils");
function addComment(xml, taskId, message, timestamp) {
    const plan = (0, xml_utils_1.parsePlanXml)(xml);
    const task = plan.tasks.find((t) => t.id === taskId);
    if (!task) {
        throw new Error(`Task ${taskId} not found in XML`);
    }
    task.comments.push({ timestamp, message });
    return (0, xml_utils_1.serializePlanXml)(plan);
}
// CLI entrypoint
if (require.main === module) {
    const args = process.argv.slice(2);
    const planIdx = args.indexOf('--plan');
    const taskIdx = args.indexOf('--task');
    const msgIdx = args.indexOf('--message');
    if (planIdx === -1 || taskIdx === -1 || msgIdx === -1) {
        console.error('Usage: node add-comment.js --plan <N> --task <id> --message <text>');
        process.exit(1);
    }
    const planNum = parseInt(args[planIdx + 1], 10);
    const taskId = parseInt(args[taskIdx + 1], 10);
    const message = args[msgIdx + 1];
    const timestamp = new Date().toISOString();
    const xmlPath = path.join('.agents', 'plans', `plan-${planNum}-tasks.xml`);
    const xml = fs.readFileSync(xmlPath, 'utf-8');
    const updated = addComment(xml, taskId, message, timestamp);
    fs.writeFileSync(xmlPath, updated, 'utf-8');
    console.log(`Comment added to task ${taskId}`);
}
