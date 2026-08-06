//! Port of `io.bitken.ss.gw` (plan-107): gateways from the plan/task domain
//! to the outside world — git subprocesses and the XML task-file store.

mod task_store;

pub use task_store::TaskStore;
