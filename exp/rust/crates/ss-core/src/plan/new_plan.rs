//! Port of `NewPlan`: a plan that does not yet exist, knowing how to bring
//! itself into being.
//!
//! [`NewPlan::scaffold`] derives the next plan id, creates and checks out its
//! task branch, and writes a stub plan file — and deliberately does **not**
//! commit. Keeping plan-file authoring (and the absence of any git-write
//! collaborator) inside this type is what removes the "commit the file I just
//! wrote" lure from a calling agent: there is nothing here that can commit.

use std::path::PathBuf;

use crate::conf::ShipsmoothDataLocator;
use crate::gw::GitState;
use crate::plan::{branch_name, stub_markdown, PlanNumbers};
use crate::{Error, Result};

/// The two git operations scaffolding needs.
///
/// A trait rather than a bare [`GitState`] because Java's `NewPlanTest`
/// subclasses `GitState` to stub exactly these two methods (branch collision,
/// git refusing to create). Rust has no subclassing, so this is the seam that
/// replaces it — the same role the injectable clock and diagnostics sink play
/// in `gw` (plan-107).
pub trait BranchOps {
    fn branch_exists(&self, name: &str) -> bool;
    fn create_branch(&self, name: &str) -> bool;
}

impl BranchOps for GitState {
    fn branch_exists(&self, name: &str) -> bool {
        GitState::branch_exists(self, name)
    }

    fn create_branch(&self, name: &str) -> bool {
        GitState::create_branch(self, name)
    }
}

/// Outcome of scaffolding a new plan: the three facts the caller needs to
/// render a handoff — the plan id, the branch it was created on, and the stub
/// file written. Carries no I/O. Port of the Java `ScaffoldResult` record.
#[derive(Debug, PartialEq, Eq)]
pub struct ScaffoldResult {
    pub plan_id: u32,
    pub branch_name: String,
    pub plan_file: PathBuf,
}

pub struct NewPlan<G: BranchOps> {
    plan_numbers: PlanNumbers,
    git: G,
    locator: ShipsmoothDataLocator,
}

impl<G: BranchOps> NewPlan<G> {
    pub fn new(plan_numbers: PlanNumbers, git: G, locator: ShipsmoothDataLocator) -> Self {
        NewPlan { plan_numbers, git, locator }
    }

    /// Scaffolds a new plan from `desc`. Checks branch availability **before**
    /// touching the filesystem, so a collision leaves no stray stub behind —
    /// the ordering is load-bearing, and `NewPlanTest` pins it.
    pub fn scaffold(&self, desc: &str) -> Result<ScaffoldResult> {
        let plan_id = self.plan_numbers.next()?;
        let branch = branch_name(&plan_id.to_string(), desc);

        self.create_branch(&branch, plan_id)?;
        let plan_file = self.write_stub(plan_id, desc)?;

        Ok(ScaffoldResult { plan_id, branch_name: branch, plan_file })
    }

    fn create_branch(&self, branch: &str, plan_id: u32) -> Result<()> {
        if self.git.branch_exists(branch) {
            return Err(Error::Scaffold(format!(
                "branch {branch} already exists — did you mean to resume plan {plan_id}?"
            )));
        }
        if !self.git.create_branch(branch) {
            return Err(Error::Scaffold(format!("failed to create branch {branch}")));
        }
        Ok(())
    }

    fn write_stub(&self, plan_id: u32, desc: &str) -> Result<PathBuf> {
        let plan_file = self.locator.plan_markdown_file(plan_id);
        if let Some(parent) = plan_file.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&plan_file, stub_markdown(plan_id, desc))?;
        Ok(plan_file)
    }
}

#[cfg(test)]
mod tests {
    //! Port of the Java `NewPlanTest`. Its `GitState` subclass stubs become
    //! the three [`BranchOps`] fakes below.

    use super::*;
    use std::cell::RefCell;

    /// Records what it was asked to create, so a test can assert that a
    /// refused scaffold created nothing.
    struct FakeGit {
        exists: bool,
        creates: bool,
        created: RefCell<Vec<String>>,
    }

    impl FakeGit {
        fn not_existing() -> Self {
            FakeGit { exists: false, creates: true, created: RefCell::new(Vec::new()) }
        }
        fn always_existing() -> Self {
            FakeGit { exists: true, creates: true, created: RefCell::new(Vec::new()) }
        }
        fn refusing_to_create() -> Self {
            FakeGit { exists: false, creates: false, created: RefCell::new(Vec::new()) }
        }
    }

    impl BranchOps for &FakeGit {
        fn branch_exists(&self, _name: &str) -> bool {
            self.exists
        }
        fn create_branch(&self, name: &str) -> bool {
            self.created.borrow_mut().push(name.to_string());
            self.creates
        }
    }

    fn new_plan<'a>(repo: &std::path::Path, git: &'a FakeGit) -> NewPlan<&'a FakeGit> {
        let locator = ShipsmoothDataLocator::in_repo(repo).unwrap();
        let numbers = PlanNumbers::new(locator.plans_dir());
        NewPlan::new(numbers, git, ShipsmoothDataLocator::in_repo(repo).unwrap())
    }

    #[test]
    fn scaffolds_branch_and_stub_on_a_fresh_repo() {
        let repo = tempfile::tempdir().unwrap();
        let git = FakeGit::not_existing();

        let result = new_plan(repo.path(), &git).scaffold("Desktop UI").unwrap();

        assert_eq!(result.plan_id, 1);
        assert_eq!(result.branch_name, "t/1-desktop-ui");
        assert_eq!(*git.created.borrow(), vec!["t/1-desktop-ui".to_string()]);
        assert!(result.plan_file.exists());
        let body = std::fs::read_to_string(&result.plan_file).unwrap();
        assert!(body.contains("# plan-1 — Desktop UI"), "{body}");
        assert!(body.contains("## Context") && body.contains("## Tasks"), "{body}");
    }

    #[test]
    fn derives_the_next_plan_number_from_existing_files() {
        let repo = tempfile::tempdir().unwrap();
        let plans = repo.path().join(".shipsmooth/plans");
        std::fs::create_dir_all(&plans).unwrap();
        for name in ["plan-1.md", "plan-4.md"] {
            std::fs::write(plans.join(name), "x").unwrap();
        }
        let git = FakeGit::not_existing();

        let result = new_plan(repo.path(), &git).scaffold("next one").unwrap();

        assert_eq!(result.plan_id, 5, "max existing + 1, not a count");
        assert_eq!(result.branch_name, "t/5-next-one");
    }

    #[test]
    fn a_branch_collision_reports_the_resume_hint_and_writes_no_stub() {
        let repo = tempfile::tempdir().unwrap();
        let git = FakeGit::always_existing();

        let err = new_plan(repo.path(), &git).scaffold("Desktop UI").unwrap_err();

        assert!(err.to_string().contains("already exists"), "{err}");
        assert!(
            !repo.path().join(".shipsmooth/plans/plan-1.md").exists(),
            "the availability check must precede any filesystem write"
        );
        assert!(git.created.borrow().is_empty());
    }

    #[test]
    fn git_refusing_to_create_the_branch_writes_no_stub() {
        let repo = tempfile::tempdir().unwrap();
        let git = FakeGit::refusing_to_create();

        let err = new_plan(repo.path(), &git).scaffold("Desktop UI").unwrap_err();

        assert!(err.to_string().contains("failed to create branch"), "{err}");
        assert!(!repo.path().join(".shipsmooth/plans/plan-1.md").exists());
    }

    /// The fakes above stub the seam, so this is the only test that exercises
    /// the production `impl BranchOps for GitState` — scaffolding against a
    /// real repo, then scaffolding again to hit the collision branch through
    /// git itself rather than a fake.
    #[test]
    fn scaffolds_against_a_real_git_repo_through_the_gitstate_impl() {
        let repo = tempfile::tempdir().unwrap();
        let run = |args: &[&str]| {
            let ok = std::process::Command::new("git")
                .args(args)
                .current_dir(repo.path())
                .status()
                .unwrap()
                .success();
            assert!(ok, "git {args:?} failed");
        };
        run(&["init", "-q", "."]);
        run(&["-c", "user.email=t@e", "-c", "user.name=T", "commit", "-q", "--allow-empty", "-m", "seed"]);

        let locator = ShipsmoothDataLocator::in_repo(repo.path()).unwrap();
        let numbers = PlanNumbers::new(locator.plans_dir());
        let new_plan = NewPlan::new(numbers, GitState::new(repo.path()), locator);

        let result = new_plan.scaffold("Real Repo").unwrap();
        assert_eq!(result.branch_name, "t/1-real-repo");
        assert!(result.plan_file.exists());

        // The branch now exists in git, so a second scaffold of the same slug
        // must be refused by the real branch_exists, not by a fake.
        let locator2 = ShipsmoothDataLocator::in_repo(repo.path()).unwrap();
        let numbers2 = PlanNumbers::new(locator2.plans_dir());
        let again = NewPlan::new(numbers2, GitState::new(repo.path()), locator2);
        // plan-1.md exists now, so the next id is 2 — force the same slug by
        // removing the stub, which is the collision case the hint describes.
        std::fs::remove_file(&result.plan_file).unwrap();
        let err = again.scaffold("Real Repo").unwrap_err();
        assert!(err.to_string().contains("already exists"), "{err}");
    }

    #[test]
    fn slugs_are_accent_folded_and_an_empty_slug_drops_the_trailing_hyphen() {
        let repo = tempfile::tempdir().unwrap();
        let git = FakeGit::not_existing();
        assert_eq!(
            new_plan(repo.path(), &git).scaffold("Café déjà vu").unwrap().branch_name,
            "t/1-cafe-deja-vu"
        );

        let repo2 = tempfile::tempdir().unwrap();
        let git2 = FakeGit::not_existing();
        assert_eq!(new_plan(repo2.path(), &git2).scaffold("!!!").unwrap().branch_name, "t/1");
    }
}
