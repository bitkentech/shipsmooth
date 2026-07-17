//! File I/O parity tests for the model (plan-102 Task 3): atomic save and the
//! read-retry behaviour ported from Java `TaskStore`.

use ss_core::model::PlanTasks;
use std::fs;
use std::path::PathBuf;
use std::time::Instant;

fn rich_fixture() -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/xml/02-rich.xml");
    fs::read_to_string(path).unwrap()
}

#[test]
fn save_creates_parents_writes_atomically_and_load_reads_back() {
    let dir = tempfile::tempdir().unwrap();
    let target = dir.path().join("nested/plans/plan-103-tasks.xml");

    let plan = PlanTasks::parse(&rich_fixture()).unwrap();
    plan.save(&target).unwrap();

    // Content is exactly to_xml; the temp sibling is gone after the rename.
    assert_eq!(fs::read_to_string(&target).unwrap(), plan.to_xml());
    assert!(!target.with_extension("xml.tmp").exists());
    assert!(dir.path().join("nested/plans").read_dir().unwrap().count() == 1);

    let loaded = PlanTasks::load(&target).unwrap();
    assert_eq!(loaded.to_xml(), plan.to_xml());
}

#[test]
fn load_retries_before_failing_on_a_corrupt_file() {
    let dir = tempfile::tempdir().unwrap();
    let target = dir.path().join("plan-1-tasks.xml");
    fs::write(&target, "definitely not xml").unwrap();

    let started = Instant::now();
    let err = PlanTasks::load(&target).unwrap_err();
    // 5 attempts with 100 ms sleeps between → at least 400 ms elapsed.
    assert!(
        started.elapsed().as_millis() >= 400,
        "load gave up without retrying (elapsed {:?})",
        started.elapsed()
    );
    assert!(!err.to_string().is_empty());
}
