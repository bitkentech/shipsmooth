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

    // The whole point of the thin path: the stub is left for the human to
    // commit. `--untracked-files=all` is required — plain --porcelain
    // collapses an untracked directory to `?? .shipsmooth/` and never names
    // the file.
    let porcelain = std::process::Command::new("git")
        .args(["status", "--porcelain", "--untracked-files=all"])
        .current_dir(&fx.repo)
        .output()
        .unwrap();
    let dirty = String::from_utf8_lossy(&porcelain.stdout);
    assert!(dirty.contains("plan-1.md"), "stub must be left uncommitted, saw: {dirty}");
}

/// Spec: a branch collision is reported with the resume hint and exit 1 —
/// on **stdout**, which is the plan group's convention (unlike store/task).
#[test]
fn plan_quick_reports_a_branch_collision_without_writing_a_stub() {
    let fx = Fixture::new();
    git(&fx.repo, &["branch", "t/1-desktop-ui"]);

    fx.shipsmooth(&["plan", "quick", "--desc", "Desktop UI"])
        .assert()
        .code(1)
        .stdout(predicates::str::contains("ERROR: branch t/1-desktop-ui already exists")
            .and(predicates::str::contains("did you mean to resume plan 1?")))
        .stderr("");

    assert!(
        !fx.plans_dir().join("plan-1.md").exists(),
        "a refused scaffold must leave no stray stub"
    );
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

/// Spec: a plan whose headings are all near-misses parses to zero tasks —
/// a loud failure on stderr that writes nothing, so existing task state
/// cannot be silently clobbered. Port of Java's
/// `PlanInitDiagnosticsIntegrationTest.zeroTaskParseFailsLoudlyAndPreservesExistingXml`.
#[test]
fn plan_init_with_no_parsable_tasks_fails_loudly_and_writes_nothing() {
    let fx = Fixture::new();
    let md = fx.plans_dir().join("plan-8.md");
    std::fs::write(
        &md,
        "# Plan\n\n## Task 1: Wrong level [Low]\n\nDepends-on: 1\n\n### Task 2 - Dash not colon [Low]\n",
    )
    .unwrap();

    fx.shipsmooth(&["plan", "init", "--plan", "8", "--tasks-from", md.to_str().unwrap()])
        .assert()
        .code(1)
        .stdout("")
        .stderr(
            predicates::str::contains("Error: no tasks found in")
                .and(predicates::str::contains("### Task N:"))
                .and(predicates::str::contains("*Depends-on:"))
                .and(predicates::str::contains("task heading must be an h3")),
        );

    assert!(!fx.plans_dir().join("plan-8-tasks.xml").exists(), "nothing must be written");
}

/// Spec: a partial parse succeeds but still surfaces the skipped lines — on
/// stdout this time. Port of `partialParseSucceedsButReportsNearMissLines`.
#[test]
fn plan_init_reports_near_misses_on_stdout_when_it_succeeds() {
    let fx = Fixture::new();
    let md = fx.plans_dir().join("plan-9.md");
    std::fs::write(&md, "# Plan\n\n### Task 1: Good [Low]\n\nBody.\n\n## Task 2: Wrong level [Medium]\n")
        .unwrap();

    fx.shipsmooth(&["plan", "init", "--plan", "9", "--tasks-from", md.to_str().unwrap()])
        .assert()
        .code(0)
        .stdout(
            predicates::str::contains("Written 1 tasks to")
                .and(predicates::str::contains("line 7")),
        );
}

/// Spec: a missing plan file is reported and nothing is written.
#[test]
fn plan_init_reports_a_missing_plan_file() {
    let fx = Fixture::new();

    fx.shipsmooth(&["plan", "init", "--plan", "9", "--tasks-from", "nope.md"])
        .assert()
        .code(1)
        .stdout("")
        .stderr("Plan file not found: nope.md\n");
}

/// Spec: Java `PlanTagTest` — version derives the next vK from git tags,
/// refuses when it already exists, and the fixed kinds create their own tag.
/// All output, errors included, on stdout.
#[test]
fn plan_tag_creates_version_complete_and_abandoned_tags() {
    let fx = Fixture::new();

    fx.shipsmooth(&["plan", "tag", "--plan", "3", "--kind", "version"])
        .assert()
        .code(0)
        .stdout("Created tag: plan-3-v1\nRun: git push origin plan-3-v1\n");

    // v1 exists now, so the next version is v2 — derived from git, not a count.
    fx.shipsmooth(&["plan", "tag", "--plan", "3", "--kind", "version"])
        .assert()
        .code(0)
        .stdout("Created tag: plan-3-v2\nRun: git push origin plan-3-v2\n");

    fx.shipsmooth(&["plan", "tag", "--plan", "3", "--kind", "complete"])
        .assert()
        .code(0)
        .stdout("Created tag: plan-3-complete\nRun: git push origin plan-3-complete\n");

    // A fixed tag that already exists cannot be created again.
    fx.shipsmooth(&["plan", "tag", "--plan", "3", "--kind", "complete"])
        .assert()
        .code(1)
        .stdout("ERROR: failed to create tag plan-3-complete\n");
}

#[test]
fn plan_tag_rejects_an_unknown_kind() {
    let fx = Fixture::new();

    fx.shipsmooth(&["plan", "tag", "--plan", "3", "--kind", "bogus"])
        .assert()
        .code(1)
        .stdout("ERROR: --kind must be one of: version, complete, abandoned\n");
}

/// Spec: Java `PlanPreflightTest` — a dirty tree fails immediately, and the
/// later conditions are never reached.
#[test]
fn plan_preflight_fails_fast_on_a_dirty_tree() {
    let fx = Fixture::new();
    std::fs::write(fx.repo.join("dirty.txt"), "x").unwrap();

    fx.shipsmooth(&["plan", "preflight", "--plan", "1"])
        .assert()
        .code(1)
        .stdout("FAIL: working tree has uncommitted changes (git status --porcelain)\n");
}

#[test]
fn plan_preflight_fails_when_the_version_tag_is_absent_locally() {
    let fx = Fixture::new();

    fx.shipsmooth(&["plan", "preflight", "--plan", "1"])
        .assert()
        .code(1)
        .stdout("FAIL: version tag plan-1-v1 not found locally\n");
}

/// With a clean tree and the tag present, the remaining two conditions are
/// warnings — printed before PASS, and the exit code stays 0.
#[test]
fn plan_preflight_warns_but_passes_without_a_remote() {
    let fx = Fixture::new();
    git(&fx.repo, &["tag", "plan-1-v1"]);

    fx.shipsmooth(&["plan", "preflight", "--plan", "1"])
        .assert()
        .code(0)
        .stdout(
            predicates::str::contains("WARN: branch is not pushed or HEAD is ahead of upstream")
                .and(predicates::str::contains("WARN: version tag plan-1-v1 not found on remote"))
                .and(predicates::str::ends_with("PASS\n")),
        );
}

/// Spec: Java `PlanBranchTest` — exactly one of --issue/--plan, the issue
/// lowercased, and the collision/creation messages.
#[test]
fn plan_branch_creates_from_either_selector() {
    let fx = Fixture::new();

    fx.shipsmooth(&["plan", "branch", "--plan", "5", "--desc", "Some Work"])
        .assert()
        .code(0)
        .stdout("Created branch: t/5-some-work\nRun: git push -u origin t/5-some-work\n");

    fx.shipsmooth(&["plan", "branch", "--issue", "PB-42", "--desc", "Other Work"])
        .assert()
        .code(0)
        .stdout("Created branch: t/pb-42-other-work\nRun: git push -u origin t/pb-42-other-work\n");
}

#[test]
fn plan_branch_requires_exactly_one_selector() {
    let fx = Fixture::new();

    for args in [
        vec!["plan", "branch", "--desc", "x"],
        vec!["plan", "branch", "--issue", "PB-1", "--plan", "2", "--desc", "x"],
    ] {
        fx.shipsmooth(&args)
            .assert()
            .code(1)
            .stdout("ERROR: provide exactly one of --issue or --plan\n");
    }
}

/// Spec: Java `PlanResumeTest` — a missing task file is an expected
/// condition with its own advice, reported on **stdout** with exit 1.
#[test]
fn plan_resume_reports_a_missing_task_file_with_the_init_hint() {
    let fx = Fixture::new();

    fx.shipsmooth(&["plan", "resume", "--plan", "4"])
        .assert()
        .code(1)
        .stdout("ERROR: task file not found for plan 4 — run: shipsmooth plan init --plan 4\n")
        .stderr("");
}

/// show, resume and project-update over an initialised plan: resume adds the
/// header, show does not, and an update lands in the XML.
#[test]
fn plan_show_resume_and_update_operate_on_an_initialised_plan() {
    let fx = Fixture::new();
    let md = fx.plans_dir().join("plan-6.md");
    std::fs::write(&md, "# Plan\n\n### Task 1: Only task [High]\n").unwrap();
    fx.shipsmooth(&["plan", "init", "--plan", "6", "--tasks-from", md.to_str().unwrap()])
        .assert()
        .code(0);

    fx.shipsmooth(&["plan", "show", "--plan", "6"])
        .assert()
        .code(0)
        .stdout(predicates::str::contains("Only task").and(
            predicates::str::contains("=== Task state ===").not(),
        ));

    fx.shipsmooth(&["plan", "resume", "--plan", "6"])
        .assert()
        .code(0)
        .stdout(predicates::str::starts_with("=== Task state ===")
            .and(predicates::str::contains("Only task")));

    fx.shipsmooth(&["plan", "update", "--plan", "6", "--status", "in-review", "--message", "ready"])
        .assert()
        .code(0)
        .stdout("Project update added.\n");

    let xml = std::fs::read_to_string(fx.plans_dir().join("plan-6-tasks.xml")).unwrap();
    assert!(xml.contains("<status>in-review</status>"), "xml was: {xml}");
    assert!(xml.contains("ready"));
    assert!(xml.contains("<blocked>false</blocked>"), "absent --blocked records false");
}

/// `--blocked` is an arity-0 flag: present means blocked, absent means the
/// update records `false` (asserted above).
#[test]
fn plan_update_records_a_blocked_entry_when_the_flag_is_present() {
    let fx = Fixture::new();
    let md = fx.plans_dir().join("plan-11.md");
    std::fs::write(&md, "# Plan\n\n### Task 1: T [Low]\n").unwrap();
    fx.shipsmooth(&["plan", "init", "--plan", "11", "--tasks-from", md.to_str().unwrap()])
        .assert()
        .code(0);

    fx.shipsmooth(&["plan", "update", "--plan", "11", "--blocked", "--message", "stuck"])
        .assert()
        .code(0)
        .stdout("Project update added.\n");

    let xml = std::fs::read_to_string(fx.plans_dir().join("plan-11-tasks.xml")).unwrap();
    assert!(xml.contains("<blocked>true</blocked>"), "xml was: {xml}");
}

/// The three thin leaves' failure paths. `show` and `update` use the CLI's
/// generic stderr shape; `resume` reports XML trouble on stdout, as Java does.
#[test]
fn the_thin_leaves_report_their_failures_in_the_right_shape() {
    let fx = Fixture::new();

    fx.shipsmooth(&["plan", "show", "--plan", "77"])
        .assert()
        .code(1)
        .stdout("")
        .stderr(predicates::str::starts_with("shipsmooth: "));

    fx.shipsmooth(&["plan", "update", "--plan", "77", "--message", "x"])
        .assert()
        .code(1)
        .stdout("")
        .stderr(predicates::str::starts_with("shipsmooth: "));

    // resume finds a file but cannot parse it: its own stdout error, not the
    // missing-file advice and not the generic stderr shape.
    std::fs::write(fx.plans_dir().join("plan-78-tasks.xml"), "not xml at all").unwrap();
    fx.shipsmooth(&["plan", "resume", "--plan", "78"])
        .assert()
        .code(1)
        .stdout(predicates::str::starts_with("ERROR reading plan XML: "))
        .stderr("");
}

#[test]
fn plan_branch_refuses_an_existing_branch() {
    let fx = Fixture::new();
    git(&fx.repo, &["branch", "t/5-some-work"]);

    fx.shipsmooth(&["plan", "branch", "--plan", "5", "--desc", "Some Work"])
        .assert()
        .code(1)
        .stdout("ERROR: branch t/5-some-work already exists\n");
}
