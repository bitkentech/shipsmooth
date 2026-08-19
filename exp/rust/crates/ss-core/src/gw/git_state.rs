//! Port of `io.bitken.ss.gw.GitState`: read-only git-state queries plus local
//! branch creation, used by plan preflight, branch and resume.
//!
//! Every command runs in the configured `work_dir`, never the inherited CWD
//! (the plan-70 lesson), and git is always shelled out to rather than linked
//! as a library, so the user's git config, hooks and credential helpers keep
//! working.

use std::path::{Path, PathBuf};
use std::process::Command;

/// Where git-failure diagnostics go. The shipped default writes to stderr;
/// tests install a capturing sink, because the exact strings are contract
/// (plan-107 design decision 4) and `eprintln!` cannot be asserted on.
pub type Diagnostics = Box<dyn Fn(&str)>;

pub struct GitState {
    work_dir: PathBuf,
    report: Diagnostics,
}

impl GitState {
    pub fn new(work_dir: &Path) -> GitState {
        GitState::with_reporter(work_dir, |message| eprintln!("{message}"))
    }

    /// Diagnostics-capturing constructor — the [`Diagnostics`] seam.
    pub fn with_reporter(work_dir: &Path, report: impl Fn(&str) + 'static) -> GitState {
        GitState { work_dir: work_dir.to_path_buf(), report: Box::new(report) }
    }

    /// True when the working tree has no uncommitted changes. A git that
    /// cannot run reports no changes, so an unavailable git reads as clean —
    /// deliberate degradation, carried over from Java (plan-107 decision 3).
    pub fn is_clean(&self) -> bool {
        self.run_lines(&["status", "--porcelain"]).is_empty()
    }

    /// Current branch name, or `""` when detached or unavailable.
    pub fn current_branch(&self) -> String {
        self.run_lines(&["rev-parse", "--abbrev-ref", "HEAD"])
            .first()
            .map(|l| l.trim().to_string())
            .unwrap_or_default()
    }

    /// True when the current branch has an upstream and HEAD is not ahead of
    /// it — i.e. every local commit has been pushed.
    pub fn is_branch_pushed_and_not_ahead(&self) -> bool {
        if self.run_exit_code(&["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"]) != 0 {
            return false;
        }
        self.run_lines(&["rev-list", "--count", "@{u}..HEAD"])
            .first()
            .is_some_and(|ahead| ahead.trim() == "0")
    }

    /// True when the tag exists in the local repository.
    pub fn tag_exists_locally(&self, tag: &str) -> bool {
        self.first_line_is_non_blank(&["tag", "-l", tag])
    }

    /// True when the tag exists on `origin`.
    pub fn tag_exists_on_remote(&self, tag: &str) -> bool {
        !self.run_lines(&["ls-remote", "--tags", "origin", tag]).is_empty()
    }

    /// True when a local branch with this name already exists.
    pub fn branch_exists(&self, branch_name: &str) -> bool {
        self.first_line_is_non_blank(&["branch", "--list", branch_name])
    }

    /// Creates a local branch at HEAD and switches to it. True on success.
    pub fn create_branch(&self, branch_name: &str) -> bool {
        self.run_exit_code(&["checkout", "-b", branch_name]) == 0
    }

    /// The output lines of `git worktree list`.
    pub fn worktree_list(&self) -> Vec<String> {
        self.run_lines(&["worktree", "list"])
    }

    fn first_line_is_non_blank(&self, args: &[&str]) -> bool {
        self.run_lines(args).first().is_some_and(|l| !l.trim().is_empty())
    }

    /// Java `runLines`: stdout split into lines, and an empty list when git
    /// cannot be launched at all. stderr is discarded, as Java's unread pipe
    /// discards it.
    fn run_lines(&self, args: &[&str]) -> Vec<String> {
        match Command::new("git").args(args).current_dir(&self.work_dir).output() {
            Ok(out) => String::from_utf8_lossy(&out.stdout).lines().map(str::to_string).collect(),
            Err(_) => Vec::new(),
        }
    }

    /// Java `runExitCode`: stderr merged into stdout, the exit code returned,
    /// and a failure reported through the diagnostics seam. `-1` stands for
    /// "could not launch git", as Java's catch block returns.
    ///
    /// Java merges the two streams live via `redirectErrorStream(true)`;
    /// `output()` captures them separately, so they are concatenated instead.
    /// The ordering can differ from Java's when a command writes to both.
    fn run_exit_code(&self, args: &[&str]) -> i32 {
        let command = std::iter::once("git").chain(args.iter().copied()).collect::<Vec<_>>().join(" ");
        match Command::new("git").args(args).current_dir(&self.work_dir).output() {
            Ok(out) => {
                let exit = out.status.code().unwrap_or(-1);
                let mut merged = String::from_utf8_lossy(&out.stdout).into_owned();
                merged.push_str(&String::from_utf8_lossy(&out.stderr));
                if exit != 0 && !merged.trim().is_empty() {
                    (self.report)(&format!("{command} failed (exit {exit}): {}", merged.trim()));
                }
                exit
            }
            Err(e) => {
                (self.report)(&format!("{command} could not run: {e}"));
                -1
            }
        }
    }
}

#[cfg(test)]
mod tests {
    //! Tests ported from `GitStateTest.java`, against real temporary git
    //! repositories, plus the upstream/remote cases Java's suite cannot reach
    //! without a second repo to push to.

    use super::*;
    use std::cell::RefCell;
    use std::rc::Rc;
    use tempfile::TempDir;

    /// Captured diagnostics, so the exact Java strings can be asserted.
    type Reported = Rc<RefCell<Vec<String>>>;

    /// A one-commit repo and a GitState over it whose diagnostics are
    /// captured rather than printed — Java's `@BeforeEach initRepo`.
    fn fresh_repo() -> (TempDir, GitState, Reported) {
        let repo = tempfile::tempdir().unwrap();
        init_repo(repo.path());
        let (state, log) = capturing(repo.path());
        (repo, state, log)
    }

    fn capturing(work_dir: &Path) -> (GitState, Reported) {
        let log: Reported = Rc::new(RefCell::new(Vec::new()));
        let sink = Rc::clone(&log);
        let state =
            GitState::with_reporter(work_dir, move |m| sink.borrow_mut().push(m.to_string()));
        (state, log)
    }

    fn init_repo(dir: &Path) {
        git(dir, &["init"]);
        git(dir, &["config", "user.email", "test@test.com"]);
        git(dir, &["config", "user.name", "Test"]);
        // At least one commit, or branch and tag operations have nothing to
        // point at.
        commit(dir, "README.md", "init", "init");
    }

    fn commit(dir: &Path, file: &str, contents: &str, message: &str) {
        std::fs::write(dir.join(file), contents).unwrap();
        git(dir, &["add", "."]);
        git(dir, &["commit", "-m", message]);
    }

    fn git(dir: &Path, args: &[&str]) {
        let out = Command::new("git").args(args).current_dir(dir).output().unwrap();
        assert!(out.status.success(), "git {args:?}: {}", String::from_utf8_lossy(&out.stderr));
    }

    /// A bare repo wired up as `origin`, so the upstream-tracking and
    /// ls-remote paths can be exercised for real.
    fn with_origin(dir: &Path) -> TempDir {
        let remote = tempfile::tempdir().unwrap();
        git(remote.path(), &["init", "--bare"]);
        git(dir, &["remote", "add", "origin", remote.path().to_str().unwrap()]);
        remote
    }

    // ---- is_clean ----

    #[test]
    fn is_clean_on_a_fresh_repo() {
        let (_repo, state, _log) = fresh_repo();
        assert!(state.is_clean());
    }

    #[test]
    fn is_clean_is_false_with_an_untracked_file() {
        let (repo, state, _log) = fresh_repo();
        std::fs::write(repo.path().join("dirty.txt"), "change").unwrap();
        assert!(!state.is_clean());
    }

    #[test]
    fn is_clean_is_false_with_a_modified_tracked_file() {
        let (repo, state, _log) = fresh_repo();
        std::fs::write(repo.path().join("README.md"), "modified").unwrap();
        assert!(!state.is_clean());
    }

    // ---- branches ----

    #[test]
    fn current_branch_returns_a_name() {
        let (_repo, state, _log) = fresh_repo();
        assert!(!state.current_branch().is_empty());
    }

    #[test]
    fn branch_exists_after_creation() {
        let (repo, state, _log) = fresh_repo();
        git(repo.path(), &["branch", "t/pb-99-my-feature"]);
        assert!(state.branch_exists("t/pb-99-my-feature"));
    }

    #[test]
    fn branch_exists_is_false_for_a_non_existent_branch() {
        let (_repo, state, _log) = fresh_repo();
        assert!(!state.branch_exists("t/pb-99-no-such-branch"));
    }

    #[test]
    fn create_branch_succeeds_and_switches_branch() {
        let (_repo, state, _log) = fresh_repo();
        assert!(state.create_branch("t/pb-99-new-branch"));
        assert_eq!(state.current_branch(), "t/pb-99-new-branch");
    }

    #[test]
    fn create_branch_fails_if_the_branch_already_exists() {
        let (repo, state, _log) = fresh_repo();
        git(repo.path(), &["branch", "t/pb-99-existing"]);
        assert!(!state.create_branch("t/pb-99-existing"));
    }

    // ---- tags ----

    #[test]
    fn tag_exists_locally_after_creation() {
        let (repo, state, _log) = fresh_repo();
        git(repo.path(), &["tag", "plan-7-v1"]);
        assert!(state.tag_exists_locally("plan-7-v1"));
    }

    #[test]
    fn tag_exists_locally_is_false_when_absent() {
        let (_repo, state, _log) = fresh_repo();
        assert!(!state.tag_exists_locally("plan-7-v1"));
    }

    #[test]
    fn tag_exists_on_remote_is_false_with_no_remote() {
        let (_repo, state, _log) = fresh_repo();
        assert!(!state.tag_exists_on_remote("plan-7-v1"));
    }

    #[test]
    fn tag_exists_on_remote_sees_only_pushed_tags() {
        let (repo, state, _log) = fresh_repo();
        let _remote = with_origin(repo.path());
        git(repo.path(), &["tag", "plan-7-v1"]);

        assert!(!state.tag_exists_on_remote("plan-7-v1"), "a local-only tag is not on the remote");
        git(repo.path(), &["push", "origin", "plan-7-v1"]);
        assert!(state.tag_exists_on_remote("plan-7-v1"));
        assert!(!state.tag_exists_on_remote("plan-7-v2"));
    }

    // ---- upstream tracking ----

    #[test]
    fn is_branch_pushed_is_false_with_no_upstream() {
        let (_repo, state, _log) = fresh_repo();
        assert!(!state.is_branch_pushed_and_not_ahead());
    }

    #[test]
    fn is_branch_pushed_is_true_only_while_head_is_not_ahead() {
        let (repo, state, _log) = fresh_repo();
        let _remote = with_origin(repo.path());
        let branch = state.current_branch();
        git(repo.path(), &["push", "-u", "origin", &branch]);

        assert!(state.is_branch_pushed_and_not_ahead());

        commit(repo.path(), "later.txt", "more", "unpushed work");
        assert!(!state.is_branch_pushed_and_not_ahead(), "one unpushed commit is ahead");

        git(repo.path(), &["push"]);
        assert!(state.is_branch_pushed_and_not_ahead());
    }

    // ---- worktrees ----

    #[test]
    fn worktree_list_returns_at_least_the_main_worktree() {
        let (repo, state, _log) = fresh_repo();
        let list = state.worktree_list();
        assert!(!list.is_empty());
        // The temp dir may resolve through a symlink (/tmp on macOS), so
        // match on the final component rather than the whole path.
        let name = repo.path().file_name().unwrap().to_str().unwrap();
        assert!(list.iter().any(|l| l.contains(name)), "main worktree missing from {list:?}");
    }

    // ---- diagnostics ----

    #[test]
    fn create_branch_failure_surfaces_gits_own_stderr() {
        let (repo, state, log) = fresh_repo();
        git(repo.path(), &["branch", "t/pb-99-existing"]);

        assert!(!state.create_branch("t/pb-99-existing"));

        let reported = log.borrow().join("\n");
        assert!(reported.contains("already exists"), "git's own text is the payload: {reported}");
        assert!(
            reported.starts_with("git checkout -b t/pb-99-existing failed (exit "),
            "exact Java diagnostic shape: {reported}"
        );
    }

    #[test]
    fn a_git_that_cannot_run_reports_why_and_returns_false() {
        let repo = tempfile::tempdir().unwrap();
        let (state, log) = capturing(&repo.path().join("does-not-exist"));

        assert!(!state.create_branch("t/pb-99-anything"));

        let reported = log.borrow().join("\n");
        assert!(
            reported.starts_with("git checkout -b t/pb-99-anything could not run: "),
            "exact Java diagnostic shape: {reported}"
        );
    }

    #[test]
    fn read_queries_degrade_to_empty_output_when_git_cannot_run() {
        let repo = tempfile::tempdir().unwrap();
        let (state, log) = capturing(&repo.path().join("does-not-exist"));

        // Deliberate degradation (plan-107 decision 3): no output reads as a
        // clean tree, which is what lets preflight run outside a repo at all.
        assert!(state.is_clean());
        assert_eq!(state.current_branch(), "");
        assert!(state.worktree_list().is_empty());
        assert!(!state.branch_exists("anything"));
        assert!(!state.tag_exists_locally("plan-7-v1"));
        assert!(!state.tag_exists_on_remote("plan-7-v1"));
        assert!(log.borrow().is_empty(), "run_lines queries stay quiet: {:?}", log.borrow());

        // The one read query that does report: Java probes for an upstream
        // with runExitCode, not runLines, so a failed probe is announced.
        assert!(!state.is_branch_pushed_and_not_ahead());
        assert!(log
            .borrow()
            .iter()
            .any(|m| m.starts_with("git rev-parse --abbrev-ref --symbolic-full-name @{u} could not run: ")));
    }

    #[test]
    fn the_default_constructor_installs_the_stderr_sink() {
        let repo = tempfile::tempdir().unwrap();
        init_repo(repo.path());
        let state = GitState::new(repo.path());

        assert!(state.is_clean());
        // Drives the default sink: the diagnostic goes to the harness's
        // stderr, which is exactly why the capturing seam exists.
        git(repo.path(), &["branch", "t/pb-99-existing"]);
        assert!(!state.create_branch("t/pb-99-existing"));
    }
}
