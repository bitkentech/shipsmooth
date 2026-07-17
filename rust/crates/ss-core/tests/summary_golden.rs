//! Golden test for the plan summary formatter (plan-102 Task 4): the Java
//! CLI's `plan resume` transcript over the rich fixture is byte-for-byte the
//! resume header line plus `PlanSummaryFormatter.format(...)` — so the
//! transcript minus its first line is the expected formatter output.

use ss_core::model::PlanTasks;
use ss_core::plan::summary;
use std::fs;
use std::path::PathBuf;

fn fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../fixtures")
}

#[test]
fn summary_matches_the_java_resume_transcript() {
    let xml = fs::read_to_string(fixtures().join("xml/02-rich.xml")).unwrap();
    let transcript = fs::read_to_string(fixtures().join("transcripts/plan-resume-rich.txt")).unwrap();
    let expected = transcript
        .split_once('\n')
        .expect("transcript should start with the '=== Task state ===' line")
        .1;

    let plan = PlanTasks::parse(&xml).unwrap();
    assert_eq!(summary(&plan), expected);
}
