//! Detects a legacy `.agents/` shipsmooth data tree.
//!
//! Port of the Java `LegacyDataTreeGuard`. plan-85 renamed the data folder
//! `.agents/` → `.shipsmooth/` with no back-compat and no migration; silently
//! treating a repo carrying an old `.agents/` tree as a clean in-repo project
//! would strand the user's plan history under a name nothing reads anymore.
//! The resolver surfaces that case as `Unresolvable` rather than guessing.
//!
//! Detection keys on the shipsmooth-specific `.agents/plans/` subdirectory,
//! not a bare `.agents/` directory — the latter is becoming an ecosystem
//! convention for human-authored agent *config* and must not trip the guard.

use std::path::Path;

/// `true` if `repo_root` carries a legacy `.agents/plans/` shipsmooth data tree.
pub fn is_legacy_data_tree(repo_root: &Path) -> bool {
    repo_root.join(".agents").join("plans").is_dir()
}
