"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.parsePlanXml = parsePlanXml;
exports.serializePlanXml = serializePlanXml;
const fast_xml_parser_1 = require("fast-xml-parser");
const PARSER = new fast_xml_parser_1.XMLParser({
    isArray: (name) => ['task', 'comment', 'deviation', 'update'].includes(name),
});
const BUILDER = new fast_xml_parser_1.XMLBuilder({
    suppressEmptyNode: false,
    format: true,
    indentBy: '  ',
    ignoreAttributes: false,
    attributeNamePrefix: '@_',
});
// ── Parse ────────────────────────────────────────────────────────────────────
function parsePlanXml(xml) {
    const raw = PARSER.parse(xml);
    const root = raw['plan-tasks'];
    const planNum = Number(root['plan']);
    const planVersion = String(root['plan-version']);
    const rawMeta = root['metadata'];
    const metadata = {
        backlogIssue: String(rawMeta['backlog-issue']),
        status: String(rawMeta['status']),
        created: String(rawMeta['created']),
    };
    const rawTasks = root['tasks']['task'] ?? [];
    const tasks = rawTasks.map(parseTask);
    const rawUpdates = root['project-updates']?.['update'] ?? [];
    const projectUpdates = rawUpdates.map(parseUpdate);
    return { planNum, planVersion, metadata, tasks, projectUpdates };
}
function parseTask(raw) {
    const t = raw;
    const rawComments = t['comments'];
    const commentList = rawComments?.['comment'] ?? [];
    const rawDeviations = t['deviations'];
    const deviationList = rawDeviations?.['deviation'] ?? [];
    return {
        id: Number(t['id']),
        risk: String(t['risk']),
        status: String(t['status']),
        name: String(t['name']),
        commit: t['commit'] != null ? String(t['commit']) : '',
        createdFrom: t['created-from'] != null ? String(t['created-from']) : '',
        closedAtVersion: t['closed-at-version'] != null ? String(t['closed-at-version']) : '',
        comments: commentList.map(parseComment),
        deviations: deviationList.map(parseDeviation),
    };
}
function parseComment(raw) {
    const c = raw;
    return {
        timestamp: String(c['timestamp']),
        message: String(c['message'] ?? ''),
    };
}
function parseDeviation(raw) {
    const d = raw;
    return {
        type: String(d['type']),
        timestamp: String(d['timestamp']),
        message: String(d['message'] ?? ''),
    };
}
function parseUpdate(raw) {
    const u = raw;
    return {
        timestamp: String(u['timestamp']),
        blocked: u['blocked'] === true || u['blocked'] === 'true',
        message: String(u['message'] ?? ''),
    };
}
// ── Serialize ────────────────────────────────────────────────────────────────
function serializePlanXml(plan) {
    const obj = {
        '?xml': { '@_version': '1.0', '@_encoding': 'UTF-8' },
        'plan-tasks': {
            'plan': plan.planNum,
            'plan-version': plan.planVersion,
            'metadata': {
                'backlog-issue': plan.metadata.backlogIssue,
                'status': plan.metadata.status,
                'created': plan.metadata.created,
            },
            'tasks': {
                'task': plan.tasks.map(serializeTask),
            },
            'project-updates': {
                'update': plan.projectUpdates.map(serializeUpdate),
            },
        },
    };
    // Note: builders still need @_ for the ?xml processing instruction attributes
    return BUILDER.build(obj);
}
function serializeTask(task) {
    return {
        'id': task.id,
        'risk': task.risk,
        'status': task.status,
        'name': task.name,
        'commit': task.commit,
        'created-from': task.createdFrom,
        'closed-at-version': task.closedAtVersion,
        'comments': task.comments.length > 0
            ? { 'comment': task.comments.map(serializeComment) }
            : '',
        'deviations': task.deviations.length > 0
            ? { 'deviation': task.deviations.map(serializeDeviation) }
            : '',
    };
}
function serializeComment(c) {
    return {
        'timestamp': c.timestamp,
        'message': c.message,
    };
}
function serializeDeviation(d) {
    return {
        'type': d.type,
        'timestamp': d.timestamp,
        'message': d.message,
    };
}
function serializeUpdate(u) {
    return {
        'timestamp': u.timestamp,
        'message': u.message,
        'blocked': u.blocked,
    };
}
