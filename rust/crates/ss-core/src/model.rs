//! Typed model for `plan-{N}-tasks.xml` — replaces the xjc-generated
//! `io.bitken.ss.jaxb` package (plan-102 Task 3 spike).

/// Parsed `<plan-tasks>` document.
pub struct PlanTasks;

pub fn read_plan_tasks_str(_xml: &str) -> crate::Result<PlanTasks> {
    todo!("plan-102 Task 3 de-risk")
}

pub fn write_plan_tasks_str(_plan: &PlanTasks) -> String {
    todo!("plan-102 Task 3 de-risk")
}
