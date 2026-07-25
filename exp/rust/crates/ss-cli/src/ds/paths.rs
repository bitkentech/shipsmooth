//! Lexical path normalisation, matching Java's `toAbsolutePath().normalize()`.
//!
//! Deliberately NOT `canonicalize()`: canonicalising resolves symlinks and
//! fails on missing paths, which would break config-entry matching for
//! symlinked repos and for not-yet-created external dirs (plan-106 design
//! decision). This is pure text manipulation over path components.

use std::path::{Component, Path, PathBuf};

/// Absolutise against the current directory, then remove `.` and `..`
/// segments lexically (a `..` at the root is dropped, as Java does).
pub fn normalize_lexical(path: &Path) -> PathBuf {
    let abs = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir().unwrap_or_default().join(path)
    };
    let mut out = PathBuf::new();
    for component in abs.components() {
        match component {
            Component::CurDir => {}
            // pop() at the root is a no-op, so "/.." collapses to "/".
            Component::ParentDir => {
                out.pop();
            }
            other => out.push(other),
        }
    }
    out
}
