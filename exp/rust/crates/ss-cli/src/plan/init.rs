//! `plan init`: generate the task-tracking XML from a plan's markdown.
//!
//! Port of the Java `Init`. Two things here are contract beyond the happy
//! path: a parse yielding **zero** tasks is a loud failure that writes
//! nothing (so a mis-formatted plan can never silently clobber existing task
//! state), and the near-miss diagnostics are reported either way — on
//! **stderr** when it fails, on **stdout** when it succeeds.

use std::path::Path;

use ss_core::gw::{GitTags, TaskStore};
use ss_core::plan::{parse_with_diagnostics, Diagnostic};

/// Java caps the near-miss list it prints; the rest collapse into one line.
const MAX_REPORTED_NEAR_MISSES: usize = 10;

pub fn run(store: &TaskStore, git_tags: &GitTags, plan: u32, tasks_from: &str) -> i32 {
    let path = Path::new(tasks_from);
    if !path.exists() {
        eprintln!("Plan file not found: {tasks_from}");
        return 1;
    }
    let markdown = match std::fs::read_to_string(path) {
        Ok(text) => text,
        Err(e) => {
            eprintln!("Plan file not found: {tasks_from}: {e}");
            return 1;
        }
    };

    let (tasks, diagnostics) = parse_with_diagnostics(&markdown);
    if tasks.is_empty() {
        eprintln!("Error: no tasks found in {tasks_from} — nothing written.");
        eprintln!("Expected task headings: ### Task N: Short task name [High|Medium|Low]");
        eprintln!(
            "Optional dependency line (first body line after its heading): *Depends-on: 1,2*"
        );
        for line in near_miss_report(&diagnostics) {
            eprintln!("{line}");
        }
        return 1;
    }

    let plan_version = git_tags.get_plan_version(plan);
    let generated = match store.generate_plan_tasks(plan, &plan_version, &tasks) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("shipsmooth: {e}");
            return 1;
        }
    };
    if let Err(e) = store.save_plan(plan, &generated) {
        eprintln!("shipsmooth: {e}");
        return 1;
    }

    // Report the path via the resolved store (the actual state root) rather
    // than a throwaway locator assuming in-repo-at-CWD.
    println!("Written {} tasks to {}", tasks.len(), store.plan_tasks_file(plan).display());
    for line in near_miss_report(&diagnostics) {
        println!("{line}");
    }
    0
}

/// The near-miss block, or nothing when the parse was clean. Returned as
/// lines so the caller picks the stream — the failure path writes to stderr,
/// the success path to stdout.
fn near_miss_report(diagnostics: &[Diagnostic]) -> Vec<String> {
    if diagnostics.is_empty() {
        return Vec::new();
    }
    let mut lines = vec![format!(
        "Skipped {} line(s) that look like task headings but do not match the grammar:",
        diagnostics.len()
    )];
    for d in diagnostics.iter().take(MAX_REPORTED_NEAR_MISSES) {
        lines.push(format!("  line {}: {}  <- {}", d.line, d.text, d.reason));
    }
    if diagnostics.len() > MAX_REPORTED_NEAR_MISSES {
        lines.push(format!("  … and {} more", diagnostics.len() - MAX_REPORTED_NEAR_MISSES));
    }
    lines
}

#[cfg(test)]
mod tests {
    use super::*;

    fn diag(line: u32) -> Diagnostic {
        Diagnostic { line, text: format!("## Task {line}: x"), reason: "r".to_string() }
    }

    #[test]
    fn a_clean_parse_reports_nothing() {
        assert!(near_miss_report(&[]).is_empty());
    }

    #[test]
    fn each_near_miss_is_listed_with_its_line_text_and_reason() {
        let lines = near_miss_report(&[diag(5)]);
        assert_eq!(
            lines,
            vec![
                "Skipped 1 line(s) that look like task headings but do not match the grammar:"
                    .to_string(),
                "  line 5: ## Task 5: x  <- r".to_string(),
            ]
        );
    }

    #[test]
    fn beyond_the_cap_the_remainder_collapses_into_one_line() {
        let many: Vec<Diagnostic> = (1..=13).map(diag).collect();

        let lines = near_miss_report(&many);

        assert_eq!(lines.len(), 1 + MAX_REPORTED_NEAR_MISSES + 1, "header + 10 + summary");
        assert_eq!(lines[0], "Skipped 13 line(s) that look like task headings but do not match the grammar:");
        assert_eq!(lines[1], "  line 1: ## Task 1: x  <- r");
        assert_eq!(lines[10], "  line 10: ## Task 10: x  <- r");
        assert_eq!(lines[11], "  … and 3 more");
    }
}
