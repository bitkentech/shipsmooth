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

/// The owned-folder marker file, at the data root (PB-360). Its presence is a
/// recorded fact that shipsmooth created this folder, rather than a heuristic.
pub const MANIFEST_FILE: &str = "manifest.toml";

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

    /// `manifest.toml` — the owned-folder marker, at the data root (PB-360).
    pub fn manifest_file(&self) -> PathBuf {
        self.data_root().join(MANIFEST_FILE)
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
    //! Ports of the Java `ShipsmoothDataLocatorValidationTest`, the locator
    //! half of `ResolvedStateRootTest`, and the locator half of
    //! `ShipsmoothDataLocatorIntegrationTest` (its TaskStore-wiring half ports
    //! with the `gw` module). Java's null-root test has no Rust equivalent.

    use super::*;

    fn two_dirs() -> (tempfile::TempDir, PathBuf, PathBuf) {
        let tmp = tempfile::tempdir().unwrap();
        let repo = tmp.path().join("proj");
        let state = tmp.path().join("proj-shipsmooth");
        std::fs::create_dir(&repo).unwrap();
        std::fs::create_dir(&state).unwrap();
        (tmp, repo, state)
    }

    // ── the layout fork: the single place the two storage shapes diverge ─────

    #[test]
    fn plans_dir_is_dotfolder_in_repo_but_bare_in_standalone() {
        let (_tmp, repo, state) = two_dirs();

        let in_repo = ShipsmoothDataLocator::in_repo(&repo).unwrap();
        assert_eq!(in_repo.plans_dir(), repo.join(".shipsmooth/plans"));

        let standalone =
            ShipsmoothDataLocator::new(&repo, ResolvedStateRoot::of(&state).unwrap()).unwrap();
        assert_eq!(standalone.plans_dir(), state.join("plans"));
    }

    #[test]
    fn manifest_file_sits_at_the_data_root_in_both_modes() {
        let (_tmp, repo, state) = two_dirs();

        let in_repo = ShipsmoothDataLocator::in_repo(&repo).unwrap();
        assert_eq!(in_repo.manifest_file(), repo.join(".shipsmooth/manifest.toml"));

        let standalone =
            ShipsmoothDataLocator::new(&repo, ResolvedStateRoot::of(&state).unwrap()).unwrap();
        assert_eq!(standalone.manifest_file(), state.join("manifest.toml"));
        // Marker and plans dir share the data root — the one place layout forks.
        assert_eq!(standalone.manifest_file().parent(), standalone.plans_dir().parent());
    }

    // ── ShipsmoothDataLocatorValidationTest ──────────────────────────────────

    #[test]
    fn accepts_accessible_repo_root_and_token() {
        let (_tmp, repo, state) = two_dirs();
        let token = ResolvedStateRoot::of(&state).unwrap();

        assert!(ShipsmoothDataLocator::new(&repo, token).is_ok());
    }

    #[test]
    fn rejects_non_existent_repo_root() {
        let (tmp, _repo, state) = two_dirs();
        let missing = tmp.path().join("nope");
        let token = ResolvedStateRoot::of(&state).unwrap();

        let err = ShipsmoothDataLocator::new(&missing, token).unwrap_err();
        assert!(
            err.to_string().contains(&missing.display().to_string()),
            "error must name the offending path: {err}"
        );
        assert!(err.to_string().starts_with("project root"));
    }

    // ── ResolvedStateRootTest.locator_acceptsTheToken_andResolvesPathsUnderIt ─

    #[test]
    fn locator_accepts_the_token_and_resolves_paths_under_it() {
        // The locator demands the token (not a bare path) for the state root;
        // it does not re-validate. With a distinct repo root this is
        // standalone mode: plans/ hangs directly off the token's state root.
        let (_tmp, repo, state) = two_dirs();
        let token = ResolvedStateRoot::of(&state).unwrap();
        let locator = ShipsmoothDataLocator::new(&repo, token).unwrap();

        assert_eq!(
            locator.plan_tasks_file(7).parent().unwrap(),
            state.join("plans")
        );
    }

    // ── ShipsmoothDataLocatorIntegrationTest (locator half) ──────────────────

    #[test]
    fn resolves_under_the_injected_repo_root_not_the_process_cwd() {
        let (_tmp, repo, _state) = two_dirs();
        let locator = ShipsmoothDataLocator::in_repo(&repo).unwrap();

        assert_eq!(
            locator.plan_tasks_file(42),
            repo.join(".shipsmooth/plans/plan-42-tasks.xml")
        );
    }

    // ── the remaining path derivations share the plans-dir source of truth ───

    #[test]
    fn plan_markdown_file_and_pattern_agree_on_the_filename_shape() {
        let (_tmp, repo, _state) = two_dirs();
        let locator = ShipsmoothDataLocator::in_repo(&repo).unwrap();

        let markdown = locator.plan_markdown_file(12);
        assert_eq!(markdown, repo.join(".shipsmooth/plans/plan-12.md"));

        let pattern = locator.plan_markdown_pattern();
        let name = markdown.file_name().unwrap().to_string_lossy().into_owned();
        let captures = pattern.captures(&name).expect("pattern must match its own filename");
        assert_eq!(&captures[1], "12");
        assert!(!pattern.is_match("plan-12-tasks.xml"));
    }
}
