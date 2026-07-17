//! Port of `PlanSummaryFormatter`: renders a [`PlanTasks`] as the
//! human-readable table the `show`/`resume` commands print. The golden
//! transcript rust/fixtures/transcripts/plan-resume-rich.txt pins the output.

use crate::model::PlanTasks;

const ID_WIDTH: usize = 3;
const RISK_WIDTH: usize = 6;
const STATUS_WIDTH: usize = 12;
const NAME_WIDTH: usize = 40;
const EM_DASH: &str = "—";

pub fn summary(plan: &PlanTasks) -> String {
    let mut out = String::new();

    let backlog = if plan.metadata.backlog_issue.is_empty() {
        EM_DASH
    } else {
        &plan.metadata.backlog_issue
    };
    out.push_str(&format!(
        "Plan {} ({})  status: {}  backlog: {}\n\n",
        plan.plan, plan.plan_version, plan.metadata.status, backlog
    ));

    let header = format!(
        "{}  {}  {}  {}  COMMIT",
        pad("ID", ID_WIDTH),
        pad("RISK", RISK_WIDTH),
        pad("STATUS", STATUS_WIDTH),
        pad("NAME", NAME_WIDTH)
    );
    out.push_str(&header);
    out.push('\n');
    out.push_str(&"-".repeat(header.chars().count()));
    out.push('\n');

    for t in &plan.tasks {
        let commit = if t.commit.is_empty() { EM_DASH } else { &t.commit };
        out.push_str(&format!(
            "{}  {}  {}  {}  {}\n",
            pad(&t.id, ID_WIDTH),
            pad(&t.risk, RISK_WIDTH),
            pad(&t.status, STATUS_WIDTH),
            pad(&t.name, NAME_WIDTH),
            commit
        ));
    }

    out.push_str("\nProject updates:\n");
    for u in &plan.project_updates {
        let flag = if is_blocked(u.blocked.as_deref()) { " [BLOCKED]" } else { "" };
        out.push_str(&format!("  {}{}  {}\n", u.timestamp, flag, u.message));
    }

    out
}

/// xs:boolean truthy lexicals — JAXB's `isBlocked()` is true for both.
fn is_blocked(lexical: Option<&str>) -> bool {
    matches!(lexical, Some("true") | Some("1"))
}

/// Java `pad`: right-pad to width, truncating anything longer.
fn pad(s: &str, width: usize) -> String {
    let count = s.chars().count();
    if count >= width {
        return s.chars().take(width).collect();
    }
    format!("{s}{}", " ".repeat(width - count))
}
