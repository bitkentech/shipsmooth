//! plan-109 preamble: end-to-end integration tests for the `plan` noun group.
//!
//! Two ends of the group, exercised through the real binary before any leaf
//! exists: `plan quick` (derive the number, create the branch, write the stub,
//! commit nothing) and `plan init` (parse the markdown, write the task XML).
//! Together they cover the scaffolding half and the task-tracking half.
//!
//! Committed red — today `plan` is not a recognised subcommand at all.

use assert_cmd::Command;
use predicates::prelude::PredicateBooleanExt;
use std::path::Path;

/// A settled in-repo project, so the resolve gate lets a state-dependent
/// command through. `.shipsmooth/plans/` existing is what settles it.
struct Fixture {
    _work: tempfile::TempDir,
    repo: std::path::PathBuf,
    config_home: std::path::PathBuf,
}

impl Fixture {
    fn new() -> Self {
        let work = tempfile::tempdir().unwrap();
        let repo = work.path().join("fixture-proj");
        let config_home = work.path().join("config");
        std::fs::create_dir_all(repo.join(".shipsmooth/plans")).unwrap();
        std::fs::create_dir_all(&config_home).unwrap();
        git(&repo, &["init", "-q", "."]);
        git(&repo, &["-c", "user.email=fixture@example.com", "-c", "user.name=Fixture",
                     "commit", "-q", "--allow-empty", "-m", "seed"]);
        Fixture { _work: work, repo, config_home }
    }

    fn shipsmooth(&self, args: &[&str]) -> Command {
        let mut cmd = Command::cargo_bin("shipsmooth").unwrap();
        cmd.args(args).current_dir(&self.repo).env("XDG_CONFIG_HOME", &self.config_home);
        cmd
    }

    fn plans_dir(&self) -> std::path::PathBuf {
        self.repo.join(".shipsmooth/plans")
    }

    fn current_branch(&self) -> String {
        let out = std::process::Command::new("git")
            .args(["rev-parse", "--abbrev-ref", "HEAD"])
            .current_dir(&self.repo)
            .output()
            .unwrap();
        String::from_utf8_lossy(&out.stdout).trim().to_string()
    }
}

fn git(dir: &Path, args: &[&str]) {
    let status = std::process::Command::new("git").args(args).current_dir(dir).status().unwrap();
    assert!(status.success(), "git {args:?} failed in {dir:?}");
}

/// Spec: the thin-context quickstart the skill itself runs — derive the next
/// plan id, create and check out its branch, write the stub, and **commit
/// nothing**. The uncommitted stub is the contract, not an accident.
#[test]
fn plan_quick_scaffolds_a_branch_and_stub_without_committing() {
    let fx = Fixture::new();

    fx.shipsmooth(&["plan", "quick", "--desc", "Desktop UI"])
        .assert()
        .code(0)
        .stdout(predicates::str::contains("Created branch: t/1-desktop-ui")
            .and(predicates::str::contains("Wrote stub: ")));

    assert_eq!(fx.current_branch(), "t/1-desktop-ui", "the new branch must be checked out");

    let stub = fx.plans_dir().join("plan-1.md");
    let text = std::fs::read_to_string(&stub).expect("stub plan file should exist");
    assert!(text.starts_with("# plan-1 — Desktop UI"), "stub was: {text}");

    // The whole point of the thin path: the stub is left for the human to commit.
    let porcelain = std::process::Command::new("git")
        .args(["status", "--porcelain"])
        .current_dir(&fx.repo)
        .output()
        .unwrap();
    let dirty = String::from_utf8_lossy(&porcelain.stdout);
    assert!(dirty.contains("plan-1.md"), "stub must be left uncommitted, saw: {dirty}");
}

/// Spec: `plan init` parses the plan markdown and writes the task XML,
/// reporting the count and the resolved path.
#[test]
fn plan_init_writes_task_xml_from_the_markdown() {
    let fx = Fixture::new();
    let md = fx.plans_dir().join("plan-7.md");
    std::fs::write(
        &md,
        "# plan-7\n\n### Task 1: First task [High]\n\n### Task 2: Second task [Low]\n*Depends-on: 1*\n",
    )
    .unwrap();

    fx.shipsmooth(&["plan", "init", "--plan", "7", "--tasks-from", md.to_str().unwrap()])
        .assert()
        .code(0)
        .stdout(predicates::str::starts_with("Written 2 tasks to "));

    let xml = std::fs::read_to_string(fx.plans_dir().join("plan-7-tasks.xml")).unwrap();
    assert!(xml.contains("<name>First task</name>"), "xml was: {xml}");
    assert!(xml.contains("<name>Second task</name>"));
    assert!(xml.contains("<depends-on>1</depends-on>"));
    assert!(xml.contains("<risk>high</risk>"));
}
