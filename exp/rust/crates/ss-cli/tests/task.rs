//! plan-108 preamble: end-to-end integration test for the `task` noun group.
//!
//! Port of the Java `AddTaskIntegrationTest`'s two cases: `task add` appends
//! a task to an existing plan's XML, assigning the next id and recording
//! `--depends-on`. Committed red before any `task` command or resolve-gate
//! wiring exists — today `task add` isn't even a recognised subcommand.

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
