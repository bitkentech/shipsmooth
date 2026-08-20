//! End-to-end integration tests for the `task` noun group (plan-108).
//!
//! Port of the Java `AddTaskIntegrationTest`'s two cases: `task add` appends
//! a task to an existing plan's XML, assigning the next id and recording
//! `--depends-on`. Plus the two contracts task 1 introduces: the resolve
//! gate for a `task` command against an unsettled project, and a missing
//! plan reported as a generic CLI error.

use assert_cmd::Command;
use ss_core::conf::ShipsmoothDataLocator;
use ss_core::gw::TaskStore;
use ss_core::plan::ParsedTask;

const PLAN_NUM: u32 = 993;

/// A throwaway project repo, settled in-repo by seeding `plan-993-tasks.xml`
/// directly via `ss-core` (mirroring the Java test's own `TaskStore` setup,
/// not the CLI) — `.shipsmooth/plans/` existing is what makes the resolver
/// consider the project settled in-repo, independent of any config file.
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
        std::fs::create_dir_all(&repo).unwrap();
        std::fs::create_dir_all(&config_home).unwrap();
        let status = std::process::Command::new("git")
            .args(["init", "-q", "."])
            .current_dir(&repo)
            .status()
            .unwrap();
        assert!(status.success());

        let locator = ShipsmoothDataLocator::in_repo(&repo).unwrap();
        let store = TaskStore::new(locator);
        let seed = ParsedTask { id: 1, name: "Seed task".to_string(), risk: "high".to_string(), depends_on: String::new() };
        let plan = store
            .generate_plan_tasks(PLAN_NUM, &format!("plan-{PLAN_NUM}-v1"), &[seed])
            .unwrap();
        store.save_plan(PLAN_NUM, &plan).unwrap();

        Fixture { _work: work, repo, config_home }
    }

    fn shipsmooth(&self, args: &[&str]) -> Command {
        let mut cmd = Command::cargo_bin("shipsmooth").unwrap();
        cmd.args(args).current_dir(&self.repo).env("XDG_CONFIG_HOME", &self.config_home);
        cmd
    }

    fn load_plan(&self) -> ss_core::model::PlanTasks {
        let locator = ShipsmoothDataLocator::in_repo(&self.repo).unwrap();
        TaskStore::new(locator).load_plan(PLAN_NUM).unwrap()
    }

    fn depends_on(&self, task_id: u32) -> String {
        let locator = ShipsmoothDataLocator::in_repo(&self.repo).unwrap();
        let store = TaskStore::new(locator);
        let plan = self.load_plan();
        store.get_depends_on(&plan, task_id).unwrap()
    }
}

/// Spec: Java `AddTaskIntegrationTest.addTaskViaCliAppendsTaskToXmlWithoutExperimentalFlag`.
#[test]
fn task_add_via_cli_appends_task_to_xml() {
    let fx = Fixture::new();

    fx.shipsmooth(&[
        "task",
        "add",
        "--plan",
        &PLAN_NUM.to_string(),
        "--name",
        "Newly added task",
        "--risk",
        "medium",
    ])
    .assert()
    .code(0)
    .stdout("Added task 2: Newly added task\n")
    .stderr("");

    let plan = fx.load_plan();
    assert_eq!(plan.tasks.len(), 2, "a second task should have been appended");
    let added = &plan.tasks[1];
    assert_eq!(added.id, "2", "next id should be max+1");
    assert_eq!(added.name, "Newly added task");
    assert_eq!(added.risk, "medium");
    assert_eq!(added.status, "pending");
    assert_eq!(added.commit, "");
    assert_eq!(added.created_from, format!("plan-{PLAN_NUM}-v1"));
}

/// Spec: Java `AddTaskIntegrationTest.addTaskViaCliRecordsDependsOn`.
#[test]
fn task_add_via_cli_records_depends_on() {
    let fx = Fixture::new();

    fx.shipsmooth(&[
        "task",
        "add",
        "--plan",
        &PLAN_NUM.to_string(),
        "--name",
        "Dependent task",
        "--risk",
        "low",
        "--depends-on",
        "1",
    ])
    .assert()
    .code(0);

    assert_eq!(fx.depends_on(2), "1", "depends-on should be persisted on the new task");
}

/// Spec: task-1 contract 1 — a `task` command run against a clean, unsettled
/// project prints the same needs-decision gate JSON `store info` would, and
/// exits 10 rather than touching any state.
#[test]
fn task_add_on_unsettled_project_emits_gate_json_and_exits_10() {
    let work = tempfile::tempdir().unwrap();
    let repo = work.path().join("fresh-proj");
    let config_home = work.path().join("config");
    std::fs::create_dir_all(&repo).unwrap();
    std::fs::create_dir_all(&config_home).unwrap();
    let status = std::process::Command::new("git")
        .args(["init", "-q", "."])
        .current_dir(&repo)
        .status()
        .unwrap();
    assert!(status.success());

    let assert = Command::cargo_bin("shipsmooth")
        .unwrap()
        .args(["task", "add", "--plan", "1", "--name", "x"])
        .current_dir(&repo)
        .env("XDG_CONFIG_HOME", &config_home)
        .assert()
        .code(10);
    let output = assert.get_output();
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("\"status\":\"needs-decision\""), "stdout was: {stdout}");
    assert!(!repo.join(".shipsmooth").exists(), "an unsettled project must not be mutated");
}

/// Spec: task-1 contract — a `task` command against a settled but
/// non-existent plan reports the generic CLI error shape (stderr, exit 1),
/// not a panic.
#[test]
fn task_add_reports_a_missing_plan() {
    let fx = Fixture::new();

    fx.shipsmooth(&["task", "add", "--plan", "1", "--name", "x"])
        .assert()
        .code(1)
        .stdout("")
        .stderr(predicates::str::starts_with("shipsmooth: "));
}

/// Spec: Java `PlanServiceTest.updateTaskStatusMutatesXml`, via the CLI.
#[test]
fn task_status_via_cli_mutates_xml() {
    let fx = Fixture::new();

    fx.shipsmooth(&["task", "status", "--plan", &PLAN_NUM.to_string(), "--task", "1", "--status", "agent-coded"])
        .assert()
        .code(0)
        .stdout("Task 1 status set to \"agent-coded\"\n")
        .stderr("");

    assert_eq!(fx.load_plan().tasks[0].status, "agent-coded");
}

/// Spec: task-3 contract — an invalid `--status` prints Java's own message
/// (no `shipsmooth: ` prefix) and exits 2, not the generic exit-1 shape.
#[test]
fn task_status_rejects_an_invalid_status_with_exit_2() {
    let fx = Fixture::new();

    fx.shipsmooth(&["task", "status", "--plan", &PLAN_NUM.to_string(), "--task", "1", "--status", "bogus"])
        .assert()
        .code(2)
        .stdout("")
        .stderr(
            "invalid status \"bogus\". Allowed values: pending, in-progress, de-risked, \
             agent-coded, closed, needs-triage, abandoned\n",
        );

    assert_eq!(fx.load_plan().tasks[0].status, "pending", "an invalid status must not mutate the plan");
}
