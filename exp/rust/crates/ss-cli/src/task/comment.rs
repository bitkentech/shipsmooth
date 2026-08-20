//! `task comment`: add a timestamped comment to a task.
//!
//! Port of the Java `AddComment` — a thin wrapper over
//! [`TaskStore::mutate`], like every leaf but `status`.

use ss_core::gw::TaskStore;

pub fn run(store: &TaskStore, plan: u32, task: u32, message: &str) -> Result<String, String> {
    store
        .mutate(plan, |p| store.add_comment(p, task, message))
        .map_err(|e| e.to_string())?;
    Ok(format!("Comment added to task {task}"))
}
