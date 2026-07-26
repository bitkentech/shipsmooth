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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn removes_cur_dir_and_collapses_parent_dir_segments() {
        assert_eq!(
            normalize_lexical(Path::new("/repos/proj/../proj/.")),
            PathBuf::from("/repos/proj")
        );
    }

    #[test]
    fn parent_dir_at_the_root_is_dropped_as_java_does() {
        assert_eq!(normalize_lexical(Path::new("/../up/../top")), PathBuf::from("/top"));
    }

    #[test]
    fn relative_paths_absolutise_against_the_current_dir() {
        let got = normalize_lexical(Path::new("some/rel"));
        assert!(got.is_absolute());
        assert!(got.ends_with("some/rel"));
    }

    #[test]
    fn missing_paths_normalise_fine_where_canonicalize_would_fail() {
        let missing = Path::new("/definitely/not/created/yet/../yet");
        assert!(missing.canonicalize().is_err(), "precondition: path must not exist");
        assert_eq!(
            normalize_lexical(missing),
            PathBuf::from("/definitely/not/created/yet")
        );
    }
}
