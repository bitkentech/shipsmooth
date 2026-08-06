//! Plan-107 preamble: end-to-end tests for `ss_core::gw` (GitState, GitTags,
//! TaskStore), written before any gw code exists. The inline expected XML is
//! the JAXB layout spec (mirrors fixtures/xml/01-fresh-init.xml).

use ss_core::conf::ShipsmoothDataLocator;
use ss_core::gw::{GitState, GitTags, TaskStore};
use ss_core::plan::ParsedTask;
use std::path::Path;
use std::process::Command;
use time::macros::datetime;

/// Byte-exact JAXB rendering of a freshly generated two-task plan under a
/// pinned clock of 2026-08-06T12:00:00.123+05:30.
const FRESH_PLAN_9: &str = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<plan-tasks>
    <plan>9</plan>
    <plan-version>plan-9-v1</plan-version>
    <metadata>
        <backlog-issue></backlog-issue>
        <status>active</status>
        <created>2026-08-06</created>
    </metadata>
    <tasks>
        <task>
            <id>1</id>
            <risk>high</risk>
            <status>pending</status>
            <name>Parse the input</name>
            <commit></commit>
            <created-from>plan-9-v1</created-from>
            <closed-at-version></closed-at-version>
            <comments/>
            <deviations/>
        </task>
        <task>
            <id>2</id>
            <risk>medium</risk>
            <status>pending</status>
            <name>Write the output</name>
            <commit></commit>
            <created-from>plan-9-v1</created-from>
            <closed-at-version></closed-at-version>
            <comments/>
            <deviations/>
            <depends-on>1</depends-on>
        </task>
    </tasks>
    <project-updates>
        <update>
            <timestamp>2026-08-06T12:00:00.123+05:30</timestamp>
            <message>Plan initialised.</message>
            <blocked>false</blocked>
        </update>
    </project-updates>
</plan-tasks>
"#;

fn spec(name: &str, risk: &str, depends_on: &str) -> ParsedTask {
    ParsedTask { id: 0, name: name.into(), risk: risk.into(), depends_on: depends_on.into() }
}

#[test]
fn task_store_lifecycle_generates_mutates_and_persists_java_identical_xml() {
    let repo = tempfile::tempdir().unwrap();
    let locator = ShipsmoothDataLocator::in_repo(repo.path()).unwrap();
    let store = TaskStore::with_clock(locator, || datetime!(2026-08-06 12:00:00.123 +05:30));

    // Generate: two tasks, one depends-on, exactly the Java element shape.
    let mut plan = store
        .generate_plan_tasks(
            9,
            "plan-9-v1",
            &[spec("Parse the input", "high", ""), spec("Write the output", "medium", "1")],
        )
        .unwrap();
    assert!(!store.plan_tasks_file_exists(9));
    store.save_plan(9, &plan).unwrap();
    assert!(store.plan_tasks_file_exists(9));
    assert_eq!(std::fs::read_to_string(store.plan_tasks_file(9)).unwrap(), FRESH_PLAN_9);

    // Mutate through every write operation, then persist and re-read.
    let assigned = store.add_task(&mut plan, &spec("Harden", "low", "1,2"), "plan-9-v2").unwrap();
    assert_eq!(assigned, 3);
    store.update_task_status(&mut plan, 1, "agent-coded").unwrap();
    store.add_comment(&mut plan, 1, "De-risk draft ready for review");
    store.add_deviation(&mut plan, 2, "minor", "Split the writer").unwrap();
    store.set_commit(&mut plan, 1, "abc123").unwrap();
    store.set_depends_on(&mut plan, 2, "").unwrap(); // blank removes the element
    store.project_update(&mut plan, Some("in-review"), Some(true), Some("Awaiting review")).unwrap();
    store.save_plan(9, &plan).unwrap();

    let reread = store.load_plan(9).unwrap();
    assert_eq!(reread.tasks.len(), 3);
    let t1 = &reread.tasks[0];
    assert_eq!(t1.status, "agent-coded");
    assert_eq!(t1.commit, "abc123");
    assert_eq!(t1.comments.len(), 1);
    assert_eq!(t1.comments[0].timestamp, "2026-08-06T12:00:00.123+05:30");
    assert_eq!(t1.comments[0].message, "De-risk draft ready for review");
    let t2 = &reread.tasks[1];
    assert_eq!(t2.deviations.len(), 1);
    assert_eq!(t2.deviations[0].kind, "minor");
    assert_eq!(store.get_depends_on(&reread, 2), "");
    let t3 = &reread.tasks[2];
    assert_eq!(t3.status, "pending");
    assert_eq!(t3.created_from, "plan-9-v2");
    assert_eq!(store.get_depends_on(&reread, 3), "1,2");
    assert_eq!(reread.metadata.status, "in-review");
    assert_eq!(reread.project_updates.len(), 2);
    assert_eq!(reread.project_updates[1].message, "Awaiting review");
    assert_eq!(reread.project_updates[1].blocked.as_deref(), Some("true"));

    // Name lookup falls back to the stringified id, exactly as Java does.
    assert_eq!(store.get_task_name(&reread, 3), "Harden");
    assert_eq!(store.get_task_name(&reread, 99), "99");
}

#[test]
fn git_state_and_tags_operate_in_the_configured_workdir() {
    let repo = tempfile::tempdir().unwrap();
    let dir = repo.path();
    git(dir, &["init"]);
    git(dir, &["config", "user.email", "test@test.com"]);
    git(dir, &["config", "user.name", "Test"]);
    std::fs::write(dir.join("README.md"), "init").unwrap();
    git(dir, &["add", "."]);
    git(dir, &["commit", "-m", "init"]);

    let state = GitState::new(dir);
    assert!(state.is_clean());
    std::fs::write(dir.join("dirty.txt"), "change").unwrap();
    assert!(!state.is_clean());
    git(dir, &["add", "."]);
    git(dir, &["commit", "-m", "second"]);
    assert!(state.is_clean());

    assert!(!state.branch_exists("t/9-gw-port"));
    assert!(state.create_branch("t/9-gw-port"));
    assert_eq!(state.current_branch(), "t/9-gw-port");
    assert!(!state.is_branch_pushed_and_not_ahead()); // no upstream configured
    assert!(!state.tag_exists_on_remote("plan-9-v1")); // no remote configured

    let tags = GitTags::new(dir);
    // "no tag" is distinct from v1: both derivations start at v1.
    assert_eq!(tags.get_plan_version(9), "plan-9-v1");
    assert_eq!(tags.next_plan_version(9), "plan-9-v1");
    assert!(tags.create_tag("plan-9-v1"));
    assert!(tags.tag_exists("plan-9-v1"));
    assert!(state.tag_exists_locally("plan-9-v1"));
    assert_eq!(tags.get_plan_version(9), "plan-9-v1");
    assert_eq!(tags.next_plan_version(9), "plan-9-v2");
    // Version derivation follows the highest tag, not the count.
    git(dir, &["tag", "plan-9-v4"]);
    assert_eq!(tags.get_plan_version(9), "plan-9-v4");
    assert_eq!(tags.next_plan_version(9), "plan-9-v5");
    // Another plan's tags never leak into plan 9's derivation.
    git(dir, &["tag", "plan-90-v9"]);
    assert_eq!(tags.next_plan_version(9), "plan-9-v5");
}

fn git(dir: &Path, args: &[&str]) {
    let out = Command::new("git").args(args).current_dir(dir).output().unwrap();
    assert!(out.status.success(), "git {args:?} failed: {}", String::from_utf8_lossy(&out.stderr));
}
