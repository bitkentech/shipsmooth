//! Project context derived from git — the repo root and origin URL.
//!
//! Ports of the Java `RepoRoot` (repo root of the CWD via
//! `git rev-parse --show-toplevel`, falling back to the start dir when git is
//! unavailable or not in a repo) and `RemoteUrl`
//! (`git remote get-url origin`, absent when there is no origin).

use std::path::{Path, PathBuf};
use std::process::Command;

/// The project this invocation runs against: the repo root and its origin URL
/// travel together through every store command (the Java `bind` pair).
pub struct ProjectContext {
    pub repo_root: PathBuf,
    pub remote_url: Option<String>,
}

impl ProjectContext {
    /// Derive the context from `start_dir`, as the Java `main` does from CWD.
    pub fn from_dir(start_dir: &Path) -> ProjectContext {
        let repo_root = repo_root(start_dir);
        let remote_url = remote_url(&repo_root);
        ProjectContext { repo_root, remote_url }
    }

    #[cfg(test)]
    pub fn without_remote(repo_root: &Path) -> ProjectContext {
        ProjectContext { repo_root: repo_root.to_path_buf(), remote_url: None }
    }

    pub fn remote_url(&self) -> Option<&str> {
        self.remote_url.as_deref()
    }
}

/// The root directory of the git repository containing `start_dir`; falls
/// back to `start_dir` itself when git is unavailable or not in a repo.
pub fn repo_root(start_dir: &Path) -> PathBuf {
    match git_line(start_dir, &["rev-parse", "--show-toplevel"]) {
        Some(line) => PathBuf::from(line),
        None => start_dir.to_path_buf(),
    }
}

/// `git remote get-url origin` for the given repo root, if present.
pub fn remote_url(repo_root: &Path) -> Option<String> {
    git_line(repo_root, &["remote", "get-url", "origin"])
}

/// First stdout line of a successful git invocation in `dir`; `None` on any
/// failure (git missing, non-zero exit, empty output).
fn git_line(dir: &Path, args: &[&str]) -> Option<String> {
    let output = Command::new("git").args(args).current_dir(dir).output().ok()?;
    if !output.status.success() {
        return None;
    }
    let line = String::from_utf8_lossy(&output.stdout).lines().next()?.trim().to_string();
    if line.is_empty() {
        None
    } else {
        Some(line)
    }
}

#[cfg(test)]
mod tests {
    //! Port of the Java `RepoRootTest`, plus the origin-URL derivation
    //! (`RemoteUrl` has no dedicated Java test file; its behaviour is pinned
    //! here the same way).

    use super::*;

    fn git(dir: &Path, args: &[&str]) {
        let status = Command::new("git").args(args).current_dir(dir).status().unwrap();
        assert!(status.success(), "git {args:?} failed in {dir:?}");
    }

    fn init_repo(tmp: &Path) -> PathBuf {
        let repo = tmp.join("myrepo");
        std::fs::create_dir(&repo).unwrap();
        git(&repo, &["init", "-q"]);
        repo
    }

    #[test]
    fn resolves_repo_root_from_subdirectory() {
        let tmp = tempfile::tempdir().unwrap();
        let repo = init_repo(tmp.path());
        let subdir = repo.join("a/b/c");
        std::fs::create_dir_all(&subdir).unwrap();

        assert_eq!(repo_root(&subdir).canonicalize().unwrap(), repo.canonicalize().unwrap());
    }

    #[test]
    fn resolves_repo_root_when_called_from_repo_root() {
        let tmp = tempfile::tempdir().unwrap();
        let repo = init_repo(tmp.path());

        assert_eq!(repo_root(&repo).canonicalize().unwrap(), repo.canonicalize().unwrap());
    }

    #[test]
    fn falls_back_to_given_dir_when_not_in_git_repo() {
        let tmp = tempfile::tempdir().unwrap();
        // GIT_CEILING can't be assumed; a fresh tempdir under the system temp
        // root is reliably outside any repo.
        assert_eq!(repo_root(tmp.path()), tmp.path());
    }

    #[test]
    fn remote_url_reports_origin_when_configured_and_absence_otherwise() {
        let tmp = tempfile::tempdir().unwrap();
        let repo = init_repo(tmp.path());

        assert_eq!(remote_url(&repo), None);

        git(&repo, &["remote", "add", "origin", "git@github.com:org/proj.git"]);
        assert_eq!(remote_url(&repo), Some("git@github.com:org/proj.git".to_string()));
    }

    #[test]
    fn context_from_dir_carries_the_repo_root_and_its_remote() {
        let tmp = tempfile::tempdir().unwrap();
        let repo = init_repo(tmp.path());
        git(&repo, &["remote", "add", "origin", "git@github.com:org/proj.git"]);
        let subdir = repo.join("sub");
        std::fs::create_dir(&subdir).unwrap();

        let context = ProjectContext::from_dir(&subdir);

        assert_eq!(
            context.repo_root.canonicalize().unwrap(),
            repo.canonicalize().unwrap()
        );
        assert_eq!(context.remote_url(), Some("git@github.com:org/proj.git"));
    }
}
