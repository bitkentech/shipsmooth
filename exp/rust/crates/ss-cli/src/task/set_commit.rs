//! `task set-commit`: record the commit hash a task landed in.
//!
//! Port of the Java `SetCommit`. Note the `--branch` option is accepted and
//! never used: Java's `PlanService.setTaskCommit` takes a `branch` argument
//! and does not pass it to `TaskStore.setCommit`. Ported as-is — the
//! migration rule is to preserve behaviour, not to fix it in flight.

use ss_core::gw::TaskStore;

pub fn run(store: &TaskStore, plan: u32, task: u32, commit: &str) -> Result<String, String> {
    store
        .mutate(plan, |p| store.set_commit(p, task, commit))
        .map_err(|e| e.to_string())?;
    Ok(format!("Commit set for task {task}"))
}
