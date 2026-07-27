//! Registry of filesystem paths for all shipsmooth data.
//!
//! Port of the Java `ShipsmoothDataLocator`. Single source of truth for path
//! construction — no other module may hardcode the data-folder name. In-repo
//! mode keeps data under the tool-owned `.shipsmooth/` folder in the project
//! repo; standalone mode points the state root at a dedicated directory that
//! *is* the data root.

use std::path::{Path, PathBuf};

use crate::conf::state_root::{validate_root, ResolvedStateRoot};

/// Tool-owned data folder used in in-repo mode (replaces the legacy `.agents/`).
const DATA_DIR: &str = ".shipsmooth";
const PLANS_SUBDIR: &str = "plans";
const PLAN_PREFIX: &str = "plan-";
const MARKDOWN_SUFFIX: &str = ".md";
const TASKS_SUFFIX: &str = "-tasks.xml";

#[derive(Debug)]
pub struct ShipsmoothDataLocator {
    repo_root: PathBuf,
    state_root: PathBuf,
}

impl ShipsmoothDataLocator {
    /// Single-root (default / in-repo) mode: data lives under the project
    /// repo, so the state root *is* the repo root. The token is minted here
    /// (validating the repo root as a state root) and handed to the two-root
    /// constructor.
    pub fn in_repo(repo_root: &Path) -> crate::Result<ShipsmoothDataLocator> {
        ShipsmoothDataLocator::new(repo_root, ResolvedStateRoot::of(repo_root)?)
    }

    /// Two-root ("separate repo") mode: `repo_root` is the project repo (git
    /// ops); `state_root` owns the data tree (plan files, etc.). The state
    /// root arrives as a `ResolvedStateRoot` token — proof it was already
    /// validated — so this constructor does not re-check it; only the project
    /// repo root is validated eagerly here (it must always exist).
    pub fn new(repo_root: &Path, state_root: ResolvedStateRoot) -> crate::Result<ShipsmoothDataLocator> {
        validate_root("project", repo_root)?;
        Ok(ShipsmoothDataLocator {
            repo_root: repo_root.to_path_buf(),
            state_root: state_root.path().to_path_buf(),
        })
    }

    /// `plans/` — the directory holding all plan files (under the data root).
    pub fn plans_dir(&self) -> PathBuf {
        self.data_root().join(PLANS_SUBDIR)
    }

    /// `plans/plan-{plan_id}-tasks.xml` under the data root.
    pub fn plan_tasks_file(&self, plan_id: u32) -> PathBuf {
        self.plans_dir().join(format!("{PLAN_PREFIX}{plan_id}{TASKS_SUFFIX}"))
    }

    /// `plans/plan-{plan_id}.md` under the data root.
    pub fn plan_markdown_file(&self, plan_id: u32) -> PathBuf {
        self.plans_dir().join(format!("{PLAN_PREFIX}{plan_id}{MARKDOWN_SUFFIX}"))
    }

    /// Regex matching a plan markdown filename, capturing the plan id.
    pub fn plan_markdown_pattern(&self) -> regex::Regex {
        let pattern = format!(
            "{}(\\d+){}",
            regex::escape(PLAN_PREFIX),
            regex::escape(MARKDOWN_SUFFIX)
        );
        regex::Regex::new(&pattern).expect("plan filename pattern is statically valid")
    }

    /// Root of the data tree. In in-repo mode (`repo_root == state_root`) the
    /// data lives under `<repo_root>/.shipsmooth`; in standalone mode the
    /// dedicated state root *is* the data root, so `plans/` hangs directly off
    /// it with no dot-folder segment.
    fn data_root(&self) -> PathBuf {
        if self.repo_root == self.state_root {
            self.state_root.join(DATA_DIR)
        } else {
            self.state_root.clone()
        }
    }
}

#[cfg(test)]
mod tests {
    //! plan-106 Task 5 de-risk: the layout difference (the single place the
    //! same-repo vs separate-dir path shapes diverge), token validation, and
    //! the locator's eager project-root check. Full test port lands in
    //! hardening.

    use super::*;

    #[test]
    fn plans_dir_is_dotfolder_in_repo_but_bare_in_standalone() {
        let tmp = tempfile::tempdir().unwrap();
        let repo = tmp.path().join("proj");
        let state = tmp.path().join("proj-shipsmooth");
        std::fs::create_dir(&repo).unwrap();
        std::fs::create_dir(&state).unwrap();

        let in_repo = ShipsmoothDataLocator::in_repo(&repo).unwrap();
        assert_eq!(in_repo.plans_dir(), repo.join(".shipsmooth/plans"));

        let standalone =
            ShipsmoothDataLocator::new(&repo, ResolvedStateRoot::of(&state).unwrap()).unwrap();
        assert_eq!(standalone.plans_dir(), state.join("plans"));
    }

    #[test]
    fn state_root_token_rejects_missing_and_non_directory_paths() {
        let tmp = tempfile::tempdir().unwrap();

        let missing = tmp.path().join("nope");
        let err = ResolvedStateRoot::of(&missing).unwrap_err();
        assert_eq!(
            err.to_string(),
            format!("state root {} is not accessible: does not exist", missing.display())
        );

        let file = tmp.path().join("file");
        std::fs::write(&file, "x").unwrap();
        let err = ResolvedStateRoot::of(&file).unwrap_err();
        assert_eq!(
            err.to_string(),
            format!("state root {} is not accessible: is not a directory", file.display())
        );
    }

    #[test]
    fn locator_validates_the_project_root_eagerly() {
        let tmp = tempfile::tempdir().unwrap();
        let missing = tmp.path().join("gone");

        let err =
            ShipsmoothDataLocator::new(&missing, ResolvedStateRoot::of(tmp.path()).unwrap())
                .unwrap_err();
        assert!(err.to_string().starts_with(&format!("project root {}", missing.display())));
    }
}
