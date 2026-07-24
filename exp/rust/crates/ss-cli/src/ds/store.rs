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
    Standalone { repo_root: PathBuf, state_dir: PathBuf },
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
