//! The three thin plan leaves: `show`, `resume` and `project-update`.
//!
//! Ports of the Java `Show`, `Resume` and `ProjectUpdate` — each is a couple
//! of lines over `TaskStore`, whose formatting and mutation behaviour is
//! already verified byte-identical (plan-107).

use ss_core::gw::TaskStore;

/// `plan show --plan N`: print the task summary. Java lets a load failure
/// propagate to picocli's handler (exit 1); the Rust equivalent reports it in
/// the CLI's generic shape.
pub fn show(store: &TaskStore, plan: u32) -> i32 {
    match store.load_plan(plan) {
        Ok(loaded) => {
            print!("{}", store.format_plan_summary(&loaded));
            0
        }
        Err(e) => {
            eprintln!("shipsmooth: {e}");
            1
        }
    }
}

/// `plan resume --plan N`: the session-resume pre-flight. Unlike `show`, a
/// missing file is an expected condition with its own advice, and both its
/// errors go to **stdout** (Java's `System.out`).
pub fn resume(store: &TaskStore, plan: u32) -> i32 {
    if !store.plan_tasks_file_exists(plan) {
        println!(
            "ERROR: task file not found for plan {plan} — run: shipsmooth plan init --plan {plan}"
        );
        return 1;
    }
    match store.load_plan(plan) {
        Ok(loaded) => {
            println!("=== Task state ===");
            print!("{}", store.format_plan_summary(&loaded));
            0
        }
        Err(e) => {
            println!("ERROR reading plan XML: {e}");
            1
        }
    }
}

/// `plan project-update --plan N [--status S] [--blocked] [--message M]`.
///
/// `--blocked` is tri-state: picocli declares it `Boolean` with no param
/// label, so it is an arity-0 flag that is `true` when present and `null`
/// when absent. `Option<bool>` carries the same three states, and
/// `TaskStore::project_update` already distinguishes them.
pub fn project_update(
    store: &TaskStore,
    plan: u32,
    status: Option<&str>,
    blocked: Option<bool>,
    message: Option<&str>,
) -> i32 {
    match store.mutate(plan, |p| store.project_update(p, status, blocked, message)) {
        Ok(()) => {
            println!("Project update added.");
            0
        }
        Err(e) => {
            eprintln!("shipsmooth: {e}");
            1
        }
    }
}
