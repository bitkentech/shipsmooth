//! Project context derived from git — the repo root and origin URL.
//!
//! Ports of the Java `RepoRoot` (repo root of the CWD via
//! `git rev-parse --show-toplevel`, falling back to the start dir when git is
//! unavailable or not in a repo) and `RemoteUrl`
//! (`git remote get-url origin`, absent when there is no origin).

use std::path::{Path, PathBuf};
use std::process::Command;

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
