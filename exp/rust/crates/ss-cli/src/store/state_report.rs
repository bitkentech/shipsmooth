//! Shared "where does state live" reporting for the `store` commands
//! (`info` and, with Task 7, `init`'s success output).
//!
//! Port of the Java `StateReport`. Emits the `ready` shape — `storageType`,
//! the state root, and the ready-to-read `plansDir` — as either a
//! machine-readable JSON line or human text.

use std::path::Path;

use ss_core::conf::{ResolvedStateRoot, ShipsmoothDataLocator};

use crate::ds::store::ProjectDataStore;
use crate::resolution_json;

/// The ready/settled state report for a store resolved at `repo_root`.
pub fn ready(repo_root: &Path, store: &ProjectDataStore, json: bool) -> String {
    let state_root = store.state_root();
    let storage_type = match store {
        ProjectDataStore::InRepo { .. } => "same-repo",
        ProjectDataStore::Standalone { .. } => "separate-dir",
    };
    // plansDir via the locator so the same-repo (.shipsmooth) vs separate-dir
    // layout difference stays owned by the single source of path truth, not
    // re-derived here. A settled store's roots were just verified by the
    // resolver, so token minting cannot fail short of a filesystem race.
    let token = ResolvedStateRoot::of(state_root).expect("settled state root must be accessible");
    let plans_dir = ShipsmoothDataLocator::new(repo_root, token)
        .expect("settled repo root must be accessible")
        .plans_dir();

    if json {
        resolution_json::ready(storage_type, state_root, &plans_dir)
    } else {
        format!("shipsmooth: {storage_type} storage at {}\nplans: {}", state_root.display(), plans_dir.display())
    }
}
