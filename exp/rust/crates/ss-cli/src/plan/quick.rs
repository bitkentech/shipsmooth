//! `plan quick`: the thin-context quickstart — derive the next plan id,
//! create the branch, write the stub, and **commit nothing**.
//!
//! Port of the Java `QuickStart`. The no-commit guarantee is not enforced
//! here; it is structural. `NewPlan` owns the scaffolding and holds no
//! git-write collaborator that could commit, so there is nothing in this
//! path capable of it. Keep it that way: handing this leaf something that
//! can commit would quietly defeat the design.

use ss_core::plan::{NewPlan, PlanNumbers, ScaffoldResult};

use crate::LeafContext;

pub fn run(cx: &LeafContext, desc: &str) -> i32 {
    let locator = crate::locator_for(&cx.repo_root, &cx.state_root);
    let numbers = PlanNumbers::new(locator.plans_dir());
    let git = ss_core::gw::GitState::new(&cx.repo_root);
    let new_plan = NewPlan::new(numbers, git, crate::locator_for(&cx.repo_root, &cx.state_root));

    match new_plan.scaffold(desc) {
        Ok(result) => {
            for line in handoff(&result) {
                println!("{line}");
            }
            0
        }
        // Java prints scaffold failures to stdout, not stderr — the plan
        // group's convention, unlike store/task. Ported as observed.
        Err(e) => {
            println!("ERROR: {e}");
            1
        }
    }
}

/// The two facts the caller needs next. Returned as lines so the wording is
/// unit-testable without capturing stdout.
fn handoff(result: &ScaffoldResult) -> Vec<String> {
    vec![
        format!("Created branch: {}", result.branch_name),
        format!("Wrote stub: {}", result.plan_file.display()),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn the_handoff_names_the_branch_and_the_stub_it_wrote() {
        let result = ScaffoldResult {
            plan_id: 7,
            branch_name: "t/7-desktop-ui".to_string(),
            plan_file: PathBuf::from("/proj/.shipsmooth/plans/plan-7.md"),
        };

        assert_eq!(
            handoff(&result),
            vec![
                "Created branch: t/7-desktop-ui".to_string(),
                "Wrote stub: /proj/.shipsmooth/plans/plan-7.md".to_string(),
            ]
        );
    }
}
