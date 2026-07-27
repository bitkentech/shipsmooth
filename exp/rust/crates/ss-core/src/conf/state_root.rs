//! Capability token proving a shipsmooth state root has been validated.
//!
//! Port of the Java `ResolvedStateRoot`. Parse-don't-validate: holding a
//! `ResolvedStateRoot` *is* the proof that its path points at an accessible
//! directory. Validation happens exactly once, in the `of` smart constructor —
//! the only way to obtain an instance. Consumers (the data locator) therefore
//! take the token instead of a bare path and never re-check the filesystem:
//! the bad case is excluded by the type, not by a runtime guard.
//!
//! The token lives in `ss-core` on purpose: it is the shared handoff contract
//! between whoever resolved the state root (the CLI's
//! `ProjectDataStoreResolver`) and the reusable data layer that serves files
//! from it.

use std::path::{Path, PathBuf};

use crate::error::Error;

#[derive(Debug)]
pub struct ResolvedStateRoot {
    path: PathBuf,
}

impl ResolvedStateRoot {
    /// Validate `state_root` and mint a token. This is the single point where
    /// an inaccessible state root is rejected. (Java also rejects a null path;
    /// Rust has no null case.)
    pub fn of(state_root: &Path) -> crate::Result<ResolvedStateRoot> {
        validate_root("state", state_root)?;
        Ok(ResolvedStateRoot { path: state_root.to_path_buf() })
    }

    /// The validated state-root directory.
    pub fn path(&self) -> &Path {
        &self.path
    }
}

/// Fail fast if a root does not point at an existing directory. Shared with
/// the locator's eager project-root check; reason texts match Java verbatim.
pub(crate) fn validate_root(role: &str, root: &Path) -> crate::Result<()> {
    let reason = if !root.exists() {
        "does not exist"
    } else if !root.is_dir() {
        "is not a directory"
    } else {
        return Ok(());
    };
    Err(Error::InaccessibleRoot {
        role: role.to_string(),
        path: root.to_path_buf(),
        reason: reason.to_string(),
    })
}

#[cfg(test)]
mod tests {
    //! Port of the Java `ResolvedStateRootTest` (its locator half lives in
    //! `locator::tests`). Java's `of_null_isRejectedAtMinting` has no Rust
    //! equivalent — the type system excludes a null path.

    use super::*;

    #[test]
    fn of_existing_directory_mints_token_carrying_the_path() {
        let dir = tempfile::tempdir().unwrap();
        let token = ResolvedStateRoot::of(dir.path()).unwrap();
        assert_eq!(token.path(), dir.path());
    }

    #[test]
    fn of_non_existent_path_is_rejected_at_minting() {
        let dir = tempfile::tempdir().unwrap();
        let missing = dir.path().join("nope");

        let err = ResolvedStateRoot::of(&missing).unwrap_err();
        assert_eq!(
            err.to_string(),
            format!("state root {} is not accessible: does not exist", missing.display())
        );
    }

    #[test]
    fn of_file_not_directory_is_rejected_at_minting() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("a-file");
        std::fs::write(&file, "x").unwrap();

        let err = ResolvedStateRoot::of(&file).unwrap_err();
        assert_eq!(
            err.to_string(),
            format!("state root {} is not accessible: is not a directory", file.display())
        );
    }
}
