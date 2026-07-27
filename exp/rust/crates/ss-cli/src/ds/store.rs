//! Where this project's shipsmooth state lives.
//!
//! Port of the Java `ProjectDataStore`: `InRepo` keeps state under the project
//! repo; `Standalone` keeps it in a separate directory. `init()` (one-time
//! setup for the standalone state repo) lands with the init leaf task.

use std::path::{Path, PathBuf};

pub enum ProjectDataStore {
    /// State lives in the project repo (default).
    InRepo { repo_root: PathBuf },
    /// State lives in a separate directory, leaving the project repo untouched.
    Standalone {
        // Java-record parity; its consumer (the init leaf) lands with Task 7.
        #[allow(dead_code)]
        repo_root: PathBuf,
        state_dir: PathBuf,
    },
}

impl ProjectDataStore {
    /// The directory under which all shipsmooth state lives.
    pub fn state_root(&self) -> &Path {
        match self {
            ProjectDataStore::InRepo { repo_root } => repo_root,
            ProjectDataStore::Standalone { state_dir, .. } => state_dir,
        }
    }
}

#[cfg(test)]
mod tests {
    //! The state-root half of the Java `ProjectDataStoreTest`; the `init()`
    //! tests port with the init leaf (Task 7).

    use super::*;

    #[test]
    fn in_repo_state_root_is_repo_root() {
        let store = ProjectDataStore::InRepo { repo_root: PathBuf::from("/proj") };
        assert_eq!(store.state_root(), Path::new("/proj"));
    }

    #[test]
    fn standalone_state_root_is_state_dir() {
        let store = ProjectDataStore::Standalone {
            repo_root: PathBuf::from("/proj"),
            state_dir: PathBuf::from("/proj-shipsmooth"),
        };
        assert_eq!(store.state_root(), Path::new("/proj-shipsmooth"));
    }
}
