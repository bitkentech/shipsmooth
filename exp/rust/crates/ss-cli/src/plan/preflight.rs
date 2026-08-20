//! `plan preflight --plan N` — the four-condition verifier that replaced the
//! step-6 bash block in the plan skill.
//!
//! Port of the Java `Preflight`. The FAIL/WARN split is the contract: a dirty
//! tree or a version tag missing locally returns 1 **immediately** (later
//! conditions are never evaluated), while an unpushed branch and a tag absent
//! from the remote are warnings printed before the final `PASS`.

use ss_core::gw::{GitState, GitTags};

pub fn run(git_state: &GitState, git_tags: &GitTags, plan: u32) -> i32 {
    // Built once, not once per use: every line costs a git subprocess, and
    // is_branch_pushed_and_not_ahead is the one read query that writes a
    // diagnostic to stderr (plan-107), so evaluating twice would double it.
    let lines = report(git_state, git_tags, plan);
    let passed = lines.last().is_some_and(|l| l == "PASS");
    for line in lines {
        println!("{line}");
    }
    if passed {
        0
    } else {
        1
    }
}

/// The lines preflight prints, in order. Returned rather than printed so the
/// fail-fast/warn-accumulate ordering is testable without capturing stdout.
fn report(git_state: &GitState, git_tags: &GitTags, plan: u32) -> Vec<String> {
    if !git_state.is_clean() {
        return vec![
            "FAIL: working tree has uncommitted changes (git status --porcelain)".to_string()
        ];
    }

    let version_tag = git_tags.get_plan_version(plan);
    if !git_state.tag_exists_locally(&version_tag) {
        return vec![format!("FAIL: version tag {version_tag} not found locally")];
    }

    let mut lines = Vec::new();
    if !git_state.is_branch_pushed_and_not_ahead() {
        lines.push("WARN: branch is not pushed or HEAD is ahead of upstream".to_string());
    }
    if !git_state.tag_exists_on_remote(&version_tag) {
        lines.push(format!("WARN: version tag {version_tag} not found on remote"));
    }
    lines.push("PASS".to_string());
    lines
}
