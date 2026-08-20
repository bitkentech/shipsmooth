//! `task deviation`: record a typed deviation against a task.
//!
//! Port of the Java `AddDeviation`. The `--type` token is validated by the
//! store's own `DeviationKind` parse (as Java's `DeviationType.fromValue`
//! does), so an unknown value surfaces as a store error and takes the
//! generic exit-1 path — unlike `status`, which validates up front.

use ss_core::gw::TaskStore;

pub fn run(store: &TaskStore, plan: u32, task: u32, kind: &str, message: &str) -> Result<String, String> {
    store
        .mutate(plan, |p| store.add_deviation(p, task, kind, message))
        .map_err(|e| e.to_string())?;
    Ok(format!("Deviation added to task {task}"))
}
