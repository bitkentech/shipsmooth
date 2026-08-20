//! `task status`: update a task's status, validating it first.
//!
//! Port of the Java `UpdateStatus`. This is the one leaf that validates
//! before calling into `TaskStore` at all, matching Java's own early branch:
//! an invalid `--status` prints its own message (no `shipsmooth: ` prefix)
//! and exits **2**, distinct from every other leaf's generic exit-1 shape.

use ss_core::gw::TaskStore;
use ss_core::model::TaskStatus;

/// `Err` carries the exact exit code and message to print verbatim — 2 for
/// an invalid status (Java's own text), 1 for everything else (the
/// generic `"shipsmooth: {msg}"` shape the caller applies).
pub fn run(store: &TaskStore, plan: u32, task: u32, status: &str) -> Result<String, (i32, String)> {
    if status.parse::<TaskStatus>().is_err() {
        let allowed: Vec<&str> = TaskStatus::ALL.iter().map(|s| s.as_str()).collect();
        return Err((2, format!("invalid status \"{status}\". Allowed values: {}", allowed.join(", "))));
    }
    store
        .mutate(plan, |p| store.update_task_status(p, task, status))
        .map_err(|e| (1, e.to_string()))?;
    Ok(format!("Task {task} status set to \"{status}\""))
}

#[cfg(test)]
mod tests {
    use super::*;
    use ss_core::conf::ShipsmoothDataLocator;
    use ss_core::plan::ParsedTask;

    fn store_in(repo: &std::path::Path) -> TaskStore {
        TaskStore::new(ShipsmoothDataLocator::in_repo(repo).unwrap())
    }

    #[test]
    fn invalid_status_reports_the_exact_java_message_and_exit_2() {
        let repo = tempfile::tempdir().unwrap();
        let store = store_in(repo.path());

        let err = run(&store, 1, 1, "bogus").unwrap_err();

        assert_eq!(
            err,
            (
                2,
                "invalid status \"bogus\". Allowed values: pending, in-progress, de-risked, \
                 agent-coded, closed, needs-triage, abandoned"
                    .to_string()
            )
        );
    }

    #[test]
    fn a_store_failure_is_reported_as_a_generic_exit_1() {
        // A valid status against a plan that does not exist: validation
        // passes, so the failure comes back from TaskStore and takes the
        // generic exit-1 path rather than the invalid-status exit-2 one.
        let repo = tempfile::tempdir().unwrap();
        let store = store_in(repo.path());

        let (code, message) = run(&store, 7, 1, "closed").unwrap_err();

        assert_eq!(code, 1);
        assert!(!message.is_empty());
    }

    #[test]
    fn valid_status_mutates_the_plan_and_reports_success() {
        let repo = tempfile::tempdir().unwrap();
        let store = store_in(repo.path());
        let seed = ParsedTask { id: 1, name: "a task".to_string(), risk: "low".to_string(), depends_on: String::new() };
        let plan = store.generate_plan_tasks(1, "plan-1-v1", &[seed]).unwrap();
        store.save_plan(1, &plan).unwrap();

        let message = run(&store, 1, 1, "agent-coded").unwrap();

        assert_eq!(message, "Task 1 status set to \"agent-coded\"");
        assert_eq!(store.load_plan(1).unwrap().tasks[0].status, "agent-coded");
    }
}
