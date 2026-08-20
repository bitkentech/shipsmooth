//! `plan branch` — create a task branch locally and print the push line.
//!
//! Port of the Java `Branch`. Takes exactly one of `--issue` / `--plan`:
//! Java computes this as `hasIssue == hasPlan` → error, so both-or-neither is
//! rejected with the same message. Enforced here at dispatch rather than with
//! a clap arg group, whose wording would differ from Java's.

use ss_core::gw::GitState;
use ss_core::plan::branch_name;

pub fn run(git_state: &GitState, issue: Option<&str>, plan: Option<u32>, desc: &str) -> i32 {
    let Some(prefix) = resolve_prefix(issue, plan) else {
        println!("ERROR: provide exactly one of --issue or --plan");
        return 1;
    };

    let branch = branch_name(&prefix, desc);
    if git_state.branch_exists(&branch) {
        println!("ERROR: branch {branch} already exists");
        return 1;
    }
    if !git_state.create_branch(&branch) {
        println!("ERROR: failed to create branch {branch}");
        return 1;
    }
    println!("Created branch: {branch}");
    println!("Run: git push -u origin {branch}");
    0
}

/// The slug prefix: the lowercased issue id, or the stringified plan number.
/// `None` when both or neither were given.
fn resolve_prefix(issue: Option<&str>, plan: Option<u32>) -> Option<String> {
    match (issue, plan) {
        (Some(issue), None) => Some(issue.to_lowercase()),
        (None, Some(plan)) => Some(plan.to_string()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exactly_one_selector_is_required() {
        assert_eq!(resolve_prefix(Some("PB-42"), None).as_deref(), Some("pb-42"), "issue is lowercased");
        assert_eq!(resolve_prefix(None, Some(7)).as_deref(), Some("7"));
        assert_eq!(resolve_prefix(None, None), None, "neither is an error");
        assert_eq!(resolve_prefix(Some("PB-42"), Some(7)), None, "both is an error too");
    }
}
