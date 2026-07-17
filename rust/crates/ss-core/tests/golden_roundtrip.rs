//! Golden round-trip acceptance test for the XML model (plan-102 Task 3).
//!
//! For every fixture written by the Java CLI: parse → serialize → the output
//! must be byte-identical to the input. This is the go/no-go gate for the
//! whole Rust migration (docs/rust-migration/00-overview.md §risks).

use ss_core::model::{PlanTasks, Risk, TaskStatus};
use std::fs;
use std::path::PathBuf;

fn fixture_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/xml")
}

#[test]
fn every_fixture_round_trips_byte_identical() {
    let mut paths: Vec<_> = fs::read_dir(fixture_dir())
        .expect("fixture dir missing — run rust/fixtures/generate.sh")
        .map(|e| e.unwrap().path())
        .filter(|p| p.extension().is_some_and(|e| e == "xml"))
        .collect();
    paths.sort();
    assert_eq!(paths.len(), 9, "fixture corpus size changed — revisit this test");

    for path in paths {
        let input = fs::read_to_string(&path).unwrap();
        let parsed = PlanTasks::parse(&input)
            .unwrap_or_else(|e| panic!("parse failed for {}: {e}", path.display()));
        let written = parsed.to_xml();

        // 06 is the one HAND-edited fixture (single-line unknown-element
        // insert). Java doesn't round-trip it byte-identically either — JAXB
        // re-indents it, which is what fixture 07 records. The agreed
        // normalization: unknown elements are preserved and re-emitted in the
        // standard pretty layout, and the writer is idempotent about it.
        if path.file_name().is_some_and(|n| n == "06-unknown-ext-input.xml") {
            assert!(written.contains("<meta-ext scope=\"fixture\">"), "unknown metadata ext lost");
            assert!(
                written.contains("<future-field attr=\"x\">unknown extension text</future-field>"),
                "unknown task ext lost"
            );
            let reparsed = PlanTasks::parse(&written).unwrap();
            assert_eq!(
                reparsed.to_xml(),
                written,
                "normalization not idempotent for {}",
                path.display()
            );
            continue;
        }

        assert_eq!(written, input, "round-trip mismatch for {}", path.display());
    }
}

#[test]
fn typed_accessors_read_the_rich_fixture() {
    let input = fs::read_to_string(fixture_dir().join("02-rich.xml")).unwrap();
    let plan = PlanTasks::parse(&input).unwrap();

    assert_eq!(plan.plan_number().unwrap(), 103);
    assert_eq!(plan.tasks.len(), 7);

    let t1 = &plan.tasks[0];
    assert_eq!(t1.id_number().unwrap(), 1);
    assert_eq!(t1.status().unwrap(), TaskStatus::DeRisked);
    assert_eq!(t1.risk_level().unwrap(), Risk::High);
    assert_eq!(t1.depends_on(), "");

    // Task 4 has the empty risk value; task 7 was added via `task add`.
    assert_eq!(plan.tasks[3].risk_level().unwrap(), Risk::Unspecified);
    assert_eq!(plan.tasks[6].depends_on(), "2,3");

    // Comment text with escapables came back unescaped exactly once.
    assert_eq!(
        t1.comments[0].message,
        "Special chars: & < > \" ' and unicode: héllo 🚀"
    );
}
