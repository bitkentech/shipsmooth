//! Golden round-trip acceptance test for the XML model (plan-102 Task 3).
//!
//! For every fixture written by the Java CLI: parse → serialize → the output
//! must be byte-identical to the input. This is the go/no-go gate for the
//! whole Rust migration (docs/rust-migration/00-overview.md §risks).

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
        let parsed = ss_core::model::read_plan_tasks_str(&input)
            .unwrap_or_else(|e| panic!("parse failed for {}: {e}", path.display()));
        let written = ss_core::model::write_plan_tasks_str(&parsed);

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
            let reparsed = ss_core::model::read_plan_tasks_str(&written).unwrap();
            assert_eq!(
                ss_core::model::write_plan_tasks_str(&reparsed),
                written,
                "normalization not idempotent for {}",
                path.display()
            );
            continue;
        }

        assert_eq!(written, input, "round-trip mismatch for {}", path.display());
    }
}
