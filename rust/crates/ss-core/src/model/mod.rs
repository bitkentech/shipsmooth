//! Typed document model for `plan-{N}-tasks.xml` — replaces the
//! xjc-generated `io.bitken.ss.jaxb` package.
//!
//! Design (plan-102 Task 3): every value is stored as the **lexical string**
//! from the file and written back verbatim, so parse → [`PlanTasks::to_xml`]
//! reproduces a Java-written file byte-for-byte (golden corpus in
//! rust/fixtures/). Reading is as lenient as JAXB unmarshalling — no enum or
//! pattern validation; the typed accessors ([`Task::status`], …) validate on
//! demand. Elements the schema doesn't know (`xs:any` slots on metadata and
//! task: `<depends-on>`, future fields) are preserved as [`RawElement`]
//! subtrees in document order.
//!
//! serde derive was ruled out for this model: it cannot capture ordered
//! `xs:any` children, cannot distinguish `<x></x>` from `<x/>`, and the exact
//! JAXB layout needs the hand-rolled [`layout`] writer regardless.

mod enums;
mod layout;
mod raw;

use std::path::{Path, PathBuf};

use crate::{Error, Result};
use layout::JaxbLayout;

pub use enums::{DeviationKind, PlanStatus, Risk, TaskStatus};
pub use raw::{RawElement, RawNode};

/// Java `TaskStore.readPlanTasks` retry: readers can race the atomic-rename
/// writer and briefly observe a missing/partial file; retrying is cheap
/// because the failure mode is rare.
const READ_ATTEMPTS: u32 = 5;
const READ_RETRY_DELAY: std::time::Duration = std::time::Duration::from_millis(100);

#[derive(Debug)]
pub struct PlanTasks {
    pub plan: String,
    pub plan_version: String,
    pub metadata: Metadata,
    pub tasks: Vec<Task>,
    pub project_updates: Vec<Update>,
}

#[derive(Debug)]
pub struct Metadata {
    pub backlog_issue: String,
    pub status: String,
    pub created: String,
    /// `xs:any` extension elements, in document order.
    pub extensions: Vec<RawElement>,
}

#[derive(Debug)]
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

#[derive(Debug)]
pub struct Comment {
    pub timestamp: String,
    pub message: String,
}

#[derive(Debug)]
pub struct Deviation {
    pub kind: String, // <type> — renamed, `type` is a keyword
    pub timestamp: String,
    pub message: String,
}

#[derive(Debug)]
pub struct Update {
    pub timestamp: String,
    pub message: String,
    pub blocked: Option<String>, // minOccurs=0 in the XSD
}

impl PlanTasks {
    pub fn parse(xml: &str) -> Result<Self> {
        let root = raw::parse_document(xml)?;
        if root.name != "plan-tasks" {
            return Err(Error::Xml(format!("unexpected root element <{}>", root.name)));
        }
        Self::from_tree(&root)
    }

    /// Reads and parses the file, retrying like Java `TaskStore.readPlanTasks`
    /// (see [`READ_ATTEMPTS`]) to paper over the rename race with a
    /// concurrent writer.
    pub fn load(path: &Path) -> Result<Self> {
        let mut attempt = 1;
        loop {
            match std::fs::read_to_string(path).map_err(Error::from).and_then(|x| Self::parse(&x)) {
                Ok(plan) => return Ok(plan),
                Err(e) if attempt >= READ_ATTEMPTS => return Err(e),
                Err(_) => {
                    attempt += 1;
                    std::thread::sleep(READ_RETRY_DELAY);
                }
            }
        }
    }

    /// Serializes in the exact JAXB formatted-output layout (see [`layout`]).
    pub fn to_xml(&self) -> String {
        let mut out = JaxbLayout::new();
        out.open(0, "plan-tasks");
        out.leaf(1, "plan", &self.plan);
        out.leaf(1, "plan-version", &self.plan_version);
        self.metadata.write(&mut out);
        self.write_tasks(&mut out);
        self.write_updates(&mut out);
        out.close(0, "plan-tasks");
        out.into_string()
    }

    /// Writes atomically like Java `TaskStore.writePlanTasks`: parent dirs
    /// created, content lands in `<path>.tmp`, then an atomic rename; the
    /// temp file is removed if anything fails.
    pub fn save(&self, path: &Path) -> Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let tmp = tmp_sibling(path);
        let written = std::fs::write(&tmp, self.to_xml()).and_then(|()| std::fs::rename(&tmp, path));
        if written.is_err() {
            let _ = std::fs::remove_file(&tmp);
        }
        written.map_err(Error::from)
    }

    pub fn plan_number(&self) -> Result<u32> {
        self.plan
            .parse()
            .map_err(|_| Error::Xml(format!("invalid plan number '{}'", self.plan)))
    }

    fn from_tree(root: &RawElement) -> Result<Self> {
        let mut plan = None;
        let mut plan_version = None;
        let mut metadata = None;
        let mut tasks = None;
        let mut project_updates = None;

        for el in root.elements() {
            match el.name.as_str() {
                "plan" => plan = Some(el.text()),
                "plan-version" => plan_version = Some(el.text()),
                "metadata" => metadata = Some(Metadata::from_tree(el)),
                "tasks" => tasks = Some(el.elements().map(Task::from_tree).collect::<Result<Vec<_>>>()?),
                "project-updates" => {
                    project_updates =
                        Some(el.elements().map(Update::from_tree).collect::<Result<Vec<_>>>()?)
                }
                other => {
                    return Err(Error::Xml(format!("unexpected element <{other}> in <plan-tasks>")))
                }
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

    fn write_tasks(&self, out: &mut JaxbLayout) {
        if self.tasks.is_empty() {
            out.empty(1, "tasks");
            return;
        }
        out.open(1, "tasks");
        for t in &self.tasks {
            t.write(out);
        }
        out.close(1, "tasks");
    }

    fn write_updates(&self, out: &mut JaxbLayout) {
        if self.project_updates.is_empty() {
            out.empty(1, "project-updates");
            return;
        }
        out.open(1, "project-updates");
        for u in &self.project_updates {
            out.open(2, "update");
            out.leaf(3, "timestamp", &u.timestamp);
            out.leaf(3, "message", &u.message);
            if let Some(b) = &u.blocked {
                out.leaf(3, "blocked", b);
            }
            out.close(2, "update");
        }
        out.close(1, "project-updates");
    }
}

impl Metadata {
    pub fn status(&self) -> Result<PlanStatus> {
        self.status.parse()
    }

    fn from_tree(el: &RawElement) -> Self {
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
                _ => md.extensions.push(child.clone()),
            }
        }
        md
    }

    fn write(&self, out: &mut JaxbLayout) {
        out.open(1, "metadata");
        out.leaf(2, "backlog-issue", &self.backlog_issue);
        out.leaf(2, "status", &self.status);
        out.leaf(2, "created", &self.created);
        for ext in &self.extensions {
            out.raw(2, ext);
        }
        out.close(1, "metadata");
    }
}

impl Task {
    pub fn id_number(&self) -> Result<u32> {
        self.id.parse().map_err(|_| Error::Xml(format!("invalid task id '{}'", self.id)))
    }

    pub fn status(&self) -> Result<TaskStatus> {
        self.status.parse()
    }

    pub fn risk_level(&self) -> Result<Risk> {
        self.risk.parse()
    }

    /// Raw `<depends-on>` text, or `""` when absent — mirrors Java
    /// `TaskStore.getDependsOn` (the element lives in the `xs:any` slot).
    pub fn depends_on(&self) -> String {
        self.extensions
            .iter()
            .find(|e| e.name == "depends-on")
            .map(|e| e.text().trim().to_owned())
            .unwrap_or_default()
    }

    fn from_tree(el: &RawElement) -> Result<Self> {
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
                    t.comments = child
                        .elements()
                        .map(|c| Comment {
                            timestamp: c.child_text("timestamp"),
                            message: c.child_text("message"),
                        })
                        .collect()
                }
                "deviations" => {
                    t.deviations = child
                        .elements()
                        .map(|d| Deviation {
                            kind: d.child_text("type"),
                            timestamp: d.child_text("timestamp"),
                            message: d.child_text("message"),
                        })
                        .collect()
                }
                _ => t.extensions.push(child.clone()),
            }
        }
        Ok(t)
    }

    fn write(&self, out: &mut JaxbLayout) {
        out.open(2, "task");
        out.leaf(3, "id", &self.id);
        out.leaf(3, "risk", &self.risk);
        out.leaf(3, "status", &self.status);
        out.leaf(3, "name", &self.name);
        out.leaf(3, "commit", &self.commit);
        out.leaf(3, "created-from", &self.created_from);
        out.leaf(3, "closed-at-version", &self.closed_at_version);
        self.write_comments(out);
        self.write_deviations(out);
        for ext in &self.extensions {
            out.raw(3, ext);
        }
        out.close(2, "task");
    }

    fn write_comments(&self, out: &mut JaxbLayout) {
        if self.comments.is_empty() {
            out.empty(3, "comments");
            return;
        }
        out.open(3, "comments");
        for c in &self.comments {
            out.open(4, "comment");
            out.leaf(5, "timestamp", &c.timestamp);
            out.leaf(5, "message", &c.message);
            out.close(4, "comment");
        }
        out.close(3, "comments");
    }

    fn write_deviations(&self, out: &mut JaxbLayout) {
        if self.deviations.is_empty() {
            out.empty(3, "deviations");
            return;
        }
        out.open(3, "deviations");
        for d in &self.deviations {
            out.open(4, "deviation");
            out.leaf(5, "type", &d.kind);
            out.leaf(5, "timestamp", &d.timestamp);
            out.leaf(5, "message", &d.message);
            out.close(4, "deviation");
        }
        out.close(3, "deviations");
    }
}

impl Deviation {
    pub fn kind_enum(&self) -> Result<DeviationKind> {
        self.kind.parse()
    }
}

impl Update {
    fn from_tree(el: &RawElement) -> Result<Self> {
        if el.name != "update" {
            return Err(Error::Xml(format!("unexpected element <{}> in <project-updates>", el.name)));
        }
        let blocked = el.elements().find(|c| c.name == "blocked").map(|c| c.text());
        Ok(Update {
            timestamp: el.child_text("timestamp"),
            message: el.child_text("message"),
            blocked,
        })
    }
}

fn tmp_sibling(path: &Path) -> PathBuf {
    let mut os = path.as_os_str().to_owned();
    os.push(".tmp");
    PathBuf::from(os)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn minimal() -> PlanTasks {
        PlanTasks {
            plan: "1".into(),
            plan_version: "plan-1-v1".into(),
            metadata: Metadata {
                backlog_issue: String::new(),
                status: "active".into(),
                created: "2026-07-17".into(),
                extensions: Vec::new(),
            },
            tasks: Vec::new(),
            project_updates: Vec::new(),
        }
    }

    #[test]
    fn empty_containers_self_close_and_blocked_is_omitted_when_absent() {
        let mut plan = minimal();
        plan.project_updates.push(Update {
            timestamp: "2026-07-17T10:00:00.000+05:30".into(),
            message: "no blocked element".into(),
            blocked: None,
        });
        let xml = plan.to_xml();
        assert!(xml.contains("    <tasks/>\n"));
        assert!(!xml.contains("<blocked>"));
    }

    #[test]
    fn parse_rejects_foreign_root_and_missing_children() {
        let err = PlanTasks::parse("<not-plan-tasks/>").unwrap_err();
        assert_eq!(err.to_string(), "unexpected root element <not-plan-tasks>");

        let err = PlanTasks::parse("<plan-tasks><plan>1</plan></plan-tasks>").unwrap_err();
        assert_eq!(err.to_string(), "missing <plan-version> in <plan-tasks>");
    }

    #[test]
    fn typed_accessors_parse_lexical_storage_on_demand() {
        let xml = minimal().to_xml();
        let plan = PlanTasks::parse(&xml).unwrap();
        assert_eq!(plan.plan_number().unwrap(), 1);
        assert_eq!(plan.metadata.status().unwrap(), PlanStatus::Active);
    }
}
