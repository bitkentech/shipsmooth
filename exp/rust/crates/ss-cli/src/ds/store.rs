//! Where this project's shipsmooth state lives.
//!
//! Port of the Java `ProjectDataStore`: `InRepo` keeps state under the project
//! repo; `Standalone` keeps it in a separate directory.

use std::path::{Path, PathBuf};

pub enum ProjectDataStore {
    /// State lives in the project repo (default).
    InRepo { repo_root: PathBuf },
    /// State lives in a separate directory, leaving the project repo untouched.
    Standalone {
        // Java-record parity; unread because init() only provisions state_dir.
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

    /// One-time provisioning of this store. In-repo stores need none here (the
    /// init leaf creates `.shipsmooth/plans/` itself); a standalone store's
    /// state dir is created and git-inited if absent.
    ///
    /// No in-repo-state guard: `ProjectDataStoreResolver` is the single
    /// decision point and already implements "config wins" when both a
    /// configured external store and an in-repo `.shipsmooth/` exist —
    /// `init()` just provisions the chosen store.
    pub fn init(&self) -> std::io::Result<()> {
        match self {
            ProjectDataStore::InRepo { .. } => Ok(()),
            ProjectDataStore::Standalone { state_dir, .. } => init_state_repo_if_absent(state_dir),
        }
    }
}

/// Tiered check (plan-84): return early when `<stateDir>/.git` is a directory
/// (no subprocess at all); else create the dir only if absent, then `git init`
/// either way — the dir may exist without being a repo after an interrupted
/// earlier init.
fn init_state_repo_if_absent(state_dir: &Path) -> std::io::Result<()> {
    if state_dir.join(".git").is_dir() {
        return Ok(());
    }
    if !state_dir.is_dir() {
        std::fs::create_dir_all(state_dir)?;
    }
    // output() swallows stdout+stderr, as Java's redirectErrorStream pipe does;
    // only the exit code matters.
    let output = std::process::Command::new("git")
        .arg("init")
        .arg(state_dir.as_os_str())
        .output()?;
    if !output.status.success() {
        return Err(std::io::Error::other(format!("git init failed for {}", state_dir.display())));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    //! Port of the Java `ProjectDataStoreTest`: state roots and the tiered
    //! `initStateRepoIfAbsent` behaviour.

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

    fn standalone_at(tmp: &Path) -> ProjectDataStore {
        ProjectDataStore::Standalone {
            repo_root: tmp.join("proj"),
            state_dir: tmp.join("proj-shipsmooth"),
        }
    }

    #[test]
    fn init_creates_and_git_inits_an_absent_state_dir_and_is_idempotent() {
        let tmp = tempfile::tempdir().unwrap();
        let store = standalone_at(tmp.path());
        let state = tmp.path().join("proj-shipsmooth");

        store.init().unwrap();
        assert!(state.join(".git").is_dir(), "state dir must be created and git-inited");

        // Second init takes the fast path (.git present): no error, repo intact.
        let head_before = std::fs::read(state.join(".git/HEAD")).unwrap();
        store.init().unwrap();
        assert_eq!(std::fs::read(state.join(".git/HEAD")).unwrap(), head_before);
    }

    #[test]
    fn init_git_inits_a_dir_that_exists_without_being_a_repo() {
        // The middle tier: the dir may exist without being a repo after an
        // interrupted earlier init — git-init it rather than assuming.
        let tmp = tempfile::tempdir().unwrap();
        let state = tmp.path().join("proj-shipsmooth");
        std::fs::create_dir(&state).unwrap();

        standalone_at(tmp.path()).init().unwrap();

        assert!(state.join(".git").is_dir());
    }

    #[test]
    fn in_repo_init_is_a_no_op() {
        let tmp = tempfile::tempdir().unwrap();
        let store = ProjectDataStore::InRepo { repo_root: tmp.path().to_path_buf() };

        store.init().unwrap();

        assert!(!tmp.path().join(".git").exists(), "in-repo init must not touch anything");
    }
}
