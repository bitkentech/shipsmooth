//! Typed model for `plan-{N}-tasks.xml` — replaces the xjc-generated
//! `io.bitken.ss.jaxb` package (plan-102 Task 3 spike).
//!
//! Spike decision: quick-xml **event API**, not serde derive. Serde derive was
//! ruled out on three structural grounds: it cannot capture ordered `xs:any`
//! children as raw elements, it cannot distinguish `<x></x>` (empty string
//! leaf) from `<x/>` (empty container) — a distinction JAXB's output makes —
//! and reproducing JAXB's exact layout needs a hand-rolled writer anyway.
//!
//! Reader is lenient like JAXB unmarshalling (no schema validation: enum-ish
//! fields are lexical `String`s here; typed accessors come in the harden
//! phase). Writer reproduces the JAXB formatted-output layout byte-for-byte:
//! `standalone="yes"` decl, 4-space indent, empty string leaves as
//! `<name></name>`, empty containers as `<name/>`, `& < >` escaped in text
//! (quotes not), unknown elements re-indented into the pretty layout, and a
//! trailing newline.

use quick_xml::events::Event;
use quick_xml::Reader;

use crate::{Error, Result};

pub struct PlanTasks {
    pub plan: String,
    pub plan_version: String,
    pub metadata: Metadata,
    pub tasks: Vec<Task>,
    pub project_updates: Vec<Update>,
}

pub struct Metadata {
    pub backlog_issue: String,
    pub status: String,
    pub created: String,
    /// `xs:any` extension elements, in document order.
    pub extensions: Vec<RawElement>,
}

pub struct Task {
    pub id: String,
    pub risk: String,
    pub status: String,
    pub name: String,
    pub commit: String,
    pub created_from: String,
    pub closed_at_version: String,
    pub comments: Vec<Comment>,
    pub deviations: Vec<Deviation>,
    /// `xs:any` extension elements (`<depends-on>`, future fields), in order.
    pub extensions: Vec<RawElement>,
}

pub struct Comment {
    pub timestamp: String,
    pub message: String,
}

pub struct Deviation {
    pub kind: String, // <type>, renamed (type is a keyword)
    pub timestamp: String,
    pub message: String,
}

pub struct Update {
    pub timestamp: String,
    pub message: String,
    pub blocked: Option<String>, // minOccurs=0 in the XSD
}

/// An element the schema doesn't know — preserved as a tree and re-emitted in
/// the standard pretty-printed layout (JAXB does the same: fixture 06 → 07).
pub struct RawElement {
    pub name: String,
    pub attrs: Vec<(String, String)>,
    pub children: Vec<RawNode>,
}

pub enum RawNode {
    Element(RawElement),
    Text(String),
}

// ---------------------------------------------------------------------------
// Reading: generic tree parse, then mapping into the typed model.
// ---------------------------------------------------------------------------

pub fn read_plan_tasks_str(xml: &str) -> Result<PlanTasks> {
    let root = parse_root(xml)?;
    if root.name != "plan-tasks" {
        return Err(Error::Xml(format!("unexpected root element <{}>", root.name)));
    }
    map_plan_tasks(root)
}

fn xml_err(e: quick_xml::Error) -> Error {
    Error::Xml(e.to_string())
}

fn start_parts(start: &quick_xml::events::BytesStart<'_>) -> Result<(String, Vec<(String, String)>)> {
    let name = String::from_utf8_lossy(start.name().as_ref()).into_owned();
    let mut attrs = Vec::new();
    for att in start.attributes() {
        let att = att.map_err(|e| Error::Xml(e.to_string()))?;
        attrs.push((
            String::from_utf8_lossy(att.key.as_ref()).into_owned(),
            att.unescape_value().map_err(xml_err)?.into_owned(),
        ));
    }
    Ok((name, attrs))
}

fn parse_root(xml: &str) -> Result<RawElement> {
    let mut reader = Reader::from_str(xml);
    loop {
        match reader.read_event().map_err(xml_err)? {
            Event::Decl(_) | Event::Comment(_) | Event::PI(_) | Event::DocType(_) => {}
            Event::Text(t) => {
                if !t.unescape().map_err(xml_err)?.trim().is_empty() {
                    return Err(Error::Xml("unexpected text before root element".into()));
                }
            }
            Event::Start(s) => {
                let parts = start_parts(&s)?;
                return parse_element(&mut reader, parts);
            }
            Event::Empty(s) => {
                let (name, attrs) = start_parts(&s)?;
                return Ok(RawElement { name, attrs, children: Vec::new() });
            }
            Event::Eof => return Err(Error::Xml("empty document".into())),
            _ => {}
        }
    }
}

fn parse_element(
    reader: &mut Reader<&[u8]>,
    (name, attrs): (String, Vec<(String, String)>),
) -> Result<RawElement> {
    let mut children: Vec<RawNode> = Vec::new();
    let mut push_text = |children: &mut Vec<RawNode>, txt: &str| {
        if let Some(RawNode::Text(last)) = children.last_mut() {
            last.push_str(txt);
        } else {
            children.push(RawNode::Text(txt.to_owned()));
        }
    };
    loop {
        match reader.read_event().map_err(xml_err)? {
            Event::Start(s) => {
                let parts = start_parts(&s)?;
                children.push(RawNode::Element(parse_element(reader, parts)?));
            }
            Event::Empty(s) => {
                let (n, a) = start_parts(&s)?;
                children.push(RawNode::Element(RawElement { name: n, attrs: a, children: Vec::new() }));
            }
            Event::Text(t) => {
                let txt = t.unescape().map_err(xml_err)?;
                // Whitespace-only text is pretty-print formatting, not content —
                // unless it extends a non-empty text run (leaf content never
                // interleaves with elements in this format).
                if !txt.trim().is_empty() {
                    push_text(&mut children, &txt);
                }
            }
            Event::CData(c) => {
                let txt = String::from_utf8_lossy(c.as_ref()).into_owned();
                push_text(&mut children, &txt);
            }
            Event::End(_) => return Ok(RawElement { name, attrs, children }),
            Event::Eof => return Err(Error::Xml(format!("unclosed element <{name}>"))),
            _ => {}
        }
    }
}

// --- tree → typed model ------------------------------------------------------

impl RawElement {
    fn text(&self) -> String {
        let mut s = String::new();
        for c in &self.children {
            if let RawNode::Text(t) = c {
                s.push_str(t);
            }
        }
        s
    }

    fn elements(&self) -> impl Iterator<Item = &RawElement> {
        self.children.iter().filter_map(|c| match c {
            RawNode::Element(e) => Some(e),
            RawNode::Text(_) => None,
        })
    }
}

fn map_plan_tasks(root: RawElement) -> Result<PlanTasks> {
    let mut plan = None;
    let mut plan_version = None;
    let mut metadata = None;
    let mut tasks = None;
    let mut project_updates = None;

    for el in root.elements() {
        match el.name.as_str() {
            "plan" => plan = Some(el.text()),
            "plan-version" => plan_version = Some(el.text()),
            "metadata" => metadata = Some(map_metadata(el)),
            "tasks" => tasks = Some(el.elements().map(map_task).collect::<Result<Vec<_>>>()?),
            "project-updates" => {
                project_updates = Some(el.elements().map(map_update).collect::<Result<Vec<_>>>()?)
            }
            other => return Err(Error::Xml(format!("unexpected element <{other}> in <plan-tasks>"))),
        }
    }

    let missing = |what: &str| Error::Xml(format!("missing <{what}> in <plan-tasks>"));
    Ok(PlanTasks {
        plan: plan.ok_or_else(|| missing("plan"))?,
        plan_version: plan_version.ok_or_else(|| missing("plan-version"))?,
        metadata: metadata.ok_or_else(|| missing("metadata"))?,
        tasks: tasks.ok_or_else(|| missing("tasks"))?,
        project_updates: project_updates.ok_or_else(|| missing("project-updates"))?,
    })
}

fn map_metadata(el: &RawElement) -> Metadata {
    let mut md = Metadata {
        backlog_issue: String::new(),
        status: String::new(),
        created: String::new(),
        extensions: Vec::new(),
    };
    for child in el.elements() {
        match child.name.as_str() {
            "backlog-issue" => md.backlog_issue = child.text(),
            "status" => md.status = child.text(),
            "created" => md.created = child.text(),
            _ => md.extensions.push(clone_raw(child)),
        }
    }
    md
}

fn map_task(el: &RawElement) -> Result<Task> {
    if el.name != "task" {
        return Err(Error::Xml(format!("unexpected element <{}> in <tasks>", el.name)));
    }
    let mut t = Task {
        id: String::new(),
        risk: String::new(),
        status: String::new(),
        name: String::new(),
        commit: String::new(),
        created_from: String::new(),
        closed_at_version: String::new(),
        comments: Vec::new(),
        deviations: Vec::new(),
        extensions: Vec::new(),
    };
    for child in el.elements() {
        match child.name.as_str() {
            "id" => t.id = child.text(),
            "risk" => t.risk = child.text(),
            "status" => t.status = child.text(),
            "name" => t.name = child.text(),
            "commit" => t.commit = child.text(),
            "created-from" => t.created_from = child.text(),
            "closed-at-version" => t.closed_at_version = child.text(),
            "comments" => {
                for c in child.elements() {
                    t.comments.push(Comment {
                        timestamp: child_text(c, "timestamp"),
                        message: child_text(c, "message"),
                    });
                }
            }
            "deviations" => {
                for d in child.elements() {
                    t.deviations.push(Deviation {
                        kind: child_text(d, "type"),
                        timestamp: child_text(d, "timestamp"),
                        message: child_text(d, "message"),
                    });
                }
            }
            _ => t.extensions.push(clone_raw(child)),
        }
    }
    Ok(t)
}

fn map_update(el: &RawElement) -> Result<Update> {
    if el.name != "update" {
        return Err(Error::Xml(format!("unexpected element <{}> in <project-updates>", el.name)));
    }
    let blocked = el.elements().find(|c| c.name == "blocked").map(|c| c.text());
    Ok(Update {
        timestamp: child_text(el, "timestamp"),
        message: child_text(el, "message"),
        blocked,
    })
}

fn child_text(el: &RawElement, name: &str) -> String {
    el.elements().find(|c| c.name == name).map(|c| c.text()).unwrap_or_default()
}

fn clone_raw(el: &RawElement) -> RawElement {
    RawElement {
        name: el.name.clone(),
        attrs: el.attrs.clone(),
        children: el
            .children
            .iter()
            .map(|c| match c {
                RawNode::Element(e) => RawNode::Element(clone_raw(e)),
                RawNode::Text(t) => RawNode::Text(t.clone()),
            })
            .collect(),
    }
}

// ---------------------------------------------------------------------------
// Writing: reproduces JAXB formatted output byte-for-byte.
// ---------------------------------------------------------------------------

const INDENT: &str = "    ";

pub fn write_plan_tasks_str(p: &PlanTasks) -> String {
    let mut s = String::with_capacity(4096);
    s.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
    s.push_str("<plan-tasks>\n");
    leaf(&mut s, 1, "plan", &p.plan);
    leaf(&mut s, 1, "plan-version", &p.plan_version);

    push_indent(&mut s, 1);
    s.push_str("<metadata>\n");
    leaf(&mut s, 2, "backlog-issue", &p.metadata.backlog_issue);
    leaf(&mut s, 2, "status", &p.metadata.status);
    leaf(&mut s, 2, "created", &p.metadata.created);
    for ext in &p.metadata.extensions {
        raw_element(&mut s, 2, ext);
    }
    push_indent(&mut s, 1);
    s.push_str("</metadata>\n");

    if p.tasks.is_empty() {
        push_indent(&mut s, 1);
        s.push_str("<tasks/>\n");
    } else {
        push_indent(&mut s, 1);
        s.push_str("<tasks>\n");
        for t in &p.tasks {
            write_task(&mut s, t);
        }
        push_indent(&mut s, 1);
        s.push_str("</tasks>\n");
    }

    if p.project_updates.is_empty() {
        push_indent(&mut s, 1);
        s.push_str("<project-updates/>\n");
    } else {
        push_indent(&mut s, 1);
        s.push_str("<project-updates>\n");
        for u in &p.project_updates {
            push_indent(&mut s, 2);
            s.push_str("<update>\n");
            leaf(&mut s, 3, "timestamp", &u.timestamp);
            leaf(&mut s, 3, "message", &u.message);
            if let Some(b) = &u.blocked {
                leaf(&mut s, 3, "blocked", b);
            }
            push_indent(&mut s, 2);
            s.push_str("</update>\n");
        }
        push_indent(&mut s, 1);
        s.push_str("</project-updates>\n");
    }

    s.push_str("</plan-tasks>\n");
    s
}

fn write_task(s: &mut String, t: &Task) {
    push_indent(s, 2);
    s.push_str("<task>\n");
    leaf(s, 3, "id", &t.id);
    leaf(s, 3, "risk", &t.risk);
    leaf(s, 3, "status", &t.status);
    leaf(s, 3, "name", &t.name);
    leaf(s, 3, "commit", &t.commit);
    leaf(s, 3, "created-from", &t.created_from);
    leaf(s, 3, "closed-at-version", &t.closed_at_version);

    if t.comments.is_empty() {
        push_indent(s, 3);
        s.push_str("<comments/>\n");
    } else {
        push_indent(s, 3);
        s.push_str("<comments>\n");
        for c in &t.comments {
            push_indent(s, 4);
            s.push_str("<comment>\n");
            leaf(s, 5, "timestamp", &c.timestamp);
            leaf(s, 5, "message", &c.message);
            push_indent(s, 4);
            s.push_str("</comment>\n");
        }
        push_indent(s, 3);
        s.push_str("</comments>\n");
    }

    if t.deviations.is_empty() {
        push_indent(s, 3);
        s.push_str("<deviations/>\n");
    } else {
        push_indent(s, 3);
        s.push_str("<deviations>\n");
        for d in &t.deviations {
            push_indent(s, 4);
            s.push_str("<deviation>\n");
            leaf(s, 5, "type", &d.kind);
            leaf(s, 5, "timestamp", &d.timestamp);
            leaf(s, 5, "message", &d.message);
            push_indent(s, 4);
            s.push_str("</deviation>\n");
        }
        push_indent(s, 3);
        s.push_str("</deviations>\n");
    }

    for ext in &t.extensions {
        raw_element(s, 3, ext);
    }
    push_indent(s, 2);
    s.push_str("</task>\n");
}

/// Known string leaf: always paired tags, even when empty (JAXB behaviour for
/// string-typed elements: `<commit></commit>`).
fn leaf(s: &mut String, level: usize, name: &str, text: &str) {
    push_indent(s, level);
    s.push('<');
    s.push_str(name);
    s.push('>');
    s.push_str(&esc_text(text));
    s.push_str("</");
    s.push_str(name);
    s.push_str(">\n");
}

/// Unknown (`xs:any`) element, re-emitted in the standard pretty layout the
/// way JAXB re-emits foreign elements: text-only content stays inline,
/// element children go on their own lines, no children self-closes.
fn raw_element(s: &mut String, level: usize, el: &RawElement) {
    push_indent(s, level);
    s.push('<');
    s.push_str(&el.name);
    for (k, v) in &el.attrs {
        s.push(' ');
        s.push_str(k);
        s.push_str("=\"");
        s.push_str(&esc_attr(v));
        s.push('"');
    }
    let has_element_children = el.children.iter().any(|c| matches!(c, RawNode::Element(_)));
    if el.children.is_empty() {
        s.push_str("/>\n");
    } else if !has_element_children {
        s.push('>');
        s.push_str(&esc_text(&el.text()));
        s.push_str("</");
        s.push_str(&el.name);
        s.push_str(">\n");
    } else {
        s.push_str(">\n");
        for child in &el.children {
            match child {
                RawNode::Element(e) => raw_element(s, level + 1, e),
                RawNode::Text(t) => {
                    push_indent(s, level + 1);
                    s.push_str(&esc_text(t));
                    s.push('\n');
                }
            }
        }
        push_indent(s, level);
        s.push_str("</");
        s.push_str(&el.name);
        s.push_str(">\n");
    }
}

fn push_indent(s: &mut String, level: usize) {
    for _ in 0..level {
        s.push_str(INDENT);
    }
}

fn esc_text(t: &str) -> String {
    t.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
}

fn esc_attr(t: &str) -> String {
    esc_text(t).replace('"', "&quot;")
}
