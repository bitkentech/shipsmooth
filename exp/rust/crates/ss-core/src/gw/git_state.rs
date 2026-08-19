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
    //! Port of `GitStateTest`, against real temporary git repositories.

    use super::*;
    use std::cell::RefCell;
    use std::rc::Rc;

    /// A GitState whose diagnostics are captured instead of printed.
    fn capturing(work_dir: &Path) -> (GitState, Rc<RefCell<Vec<String>>>) {
        let log = Rc::new(RefCell::new(Vec::new()));
        let sink = Rc::clone(&log);
        let state = GitState::with_reporter(work_dir, move |m| sink.borrow_mut().push(m.to_string()));
        (state, log)
    }

    fn git(dir: &Path, args: &[&str]) {
        let out = Command::new("git").args(args).current_dir(dir).output().unwrap();
        assert!(out.status.success(), "git {args:?}: {}", String::from_utf8_lossy(&out.stderr));
    }

    fn init_repo(dir: &Path) {
        git(dir, &["init"]);
        git(dir, &["config", "user.email", "test@test.com"]);
        git(dir, &["config", "user.name", "Test"]);
        std::fs::write(dir.join("README.md"), "init").unwrap();
        git(dir, &["add", "."]);
        git(dir, &["commit", "-m", "init"]);
    }

    #[test]
    fn queries_and_branch_creation_work_against_a_real_repo() {
        let repo = tempfile::tempdir().unwrap();
        let dir = repo.path();
        init_repo(dir);
        let (state, _log) = capturing(dir);

        assert!(state.is_clean());
        std::fs::write(dir.join("dirty.txt"), "change").unwrap();
        assert!(!state.is_clean(), "an untracked file is a dirty tree");
        git(dir, &["add", "."]);
        git(dir, &["commit", "-m", "second"]);
        assert!(state.is_clean());

        assert!(!state.branch_exists("t/9-gw-port"));
        assert!(state.create_branch("t/9-gw-port"));
        assert!(state.branch_exists("t/9-gw-port"));
        assert_eq!(state.current_branch(), "t/9-gw-port");

        assert!(!state.tag_exists_locally("plan-7-v1"));
        git(dir, &["tag", "plan-7-v1"]);
        assert!(state.tag_exists_locally("plan-7-v1"));

        // No upstream and no remote configured.
        assert!(!state.is_branch_pushed_and_not_ahead());
        assert!(!state.tag_exists_on_remote("plan-7-v1"));

        assert!(state.worktree_list().iter().any(|l| l.contains(dir.to_str().unwrap())));
    }

    #[test]
    fn a_failing_git_returns_false_and_surfaces_its_output() {
        let repo = tempfile::tempdir().unwrap();
        let dir = repo.path();
        init_repo(dir);
        let (state, log) = capturing(dir);
        git(dir, &["branch", "t/9-existing"]);

        assert!(!state.create_branch("t/9-existing"));

        let reported = log.borrow().join("\n");
        assert!(reported.contains("already exists"), "git's own text is the payload: {reported}");
        assert!(
            reported.starts_with("git checkout -b t/9-existing failed (exit "),
            "exact Java diagnostic shape: {reported}"
        );
    }

    #[test]
    fn a_git_that_cannot_run_degrades_quietly_but_reports_writes() {
        let repo = tempfile::tempdir().unwrap();
        let missing = repo.path().join("does-not-exist");
        let (state, log) = capturing(&missing);

        // Read paths degrade silently to "no output".
        assert!(state.is_clean(), "an unrunnable git reads as a clean tree");
        assert_eq!(state.current_branch(), "");
        assert!(state.worktree_list().is_empty());
        assert!(!state.branch_exists("anything"));
        assert!(log.borrow().is_empty(), "read paths stay quiet");

        // Write paths report why.
        assert!(!state.create_branch("t/9-anything"));
        let reported = log.borrow().join("\n");
        assert!(
            reported.starts_with("git checkout -b t/9-anything could not run: "),
            "exact Java diagnostic shape: {reported}"
        );
    }
}
