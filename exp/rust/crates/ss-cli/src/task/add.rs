//! `task add`: append a new task to an existing plan's XML.
//!
//! Port of the Java `AddTask`. The id is auto-assigned (max existing id + 1)
//! and `created-from` is resolved from the current plan-version git tag,
//! mirroring how `plan init` stamps freshly-generated tasks.

use ss_core::gw::{GitTags, TaskStore};
use ss_core::plan::ParsedTask;

pub fn run(
    store: &TaskStore,
    git_tags: &GitTags,
    plan: u32,
    name: &str,
    risk: &str,
    depends_on: &str,
) -> Result<String, String> {
    let plan_version = git_tags.get_plan_version(plan);
    let mut loaded = store.load_plan(plan).map_err(|e| e.to_string())?;
    let spec = ParsedTask {
        id: 0,
        name: name.to_string(),
        risk: risk.to_string(),
        depends_on: depends_on.to_string(),
    };
    let id = store.add_task(&mut loaded, &spec, &plan_version).map_err(|e| e.to_string())?;
    store.save_plan(plan, &loaded).map_err(|e| e.to_string())?;
    Ok(format!("Added task {id}: {name}"))
}
