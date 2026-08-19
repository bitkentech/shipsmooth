//! Port of `io.bitken.ss.gw` (plan-107): gateways from the plan/task domain
//! to the outside world — git subprocesses and the XML task-file store.

mod git_state;
mod task_store;
pub mod xml_time;

pub use git_state::{Diagnostics, GitState};
pub use task_store::TaskStore;
