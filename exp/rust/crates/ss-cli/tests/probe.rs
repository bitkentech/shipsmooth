//! Task 6 footprint spike: the hidden `probe` subcommand must genuinely
//! exercise every runtime crate (quick-xml, regex, unicode-normalization,
//! time, serde, serde_json, toml_edit, clap, thiserror) so the release
//! binary's size/memory measurements reflect a dependency-complete build,
//! not a dead-stripped skeleton.

use assert_cmd::Command;

fn fixtures_dir() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/xml")
}

#[test]
fn probe_round_trips_the_corpus_and_reports_json() {
    let out = Command::cargo_bin("shipsmooth")
        .unwrap()
        .args(["probe", "--dir"])
        .arg(fixtures_dir())
        .assert()
        .success();
    let stdout = String::from_utf8(out.get_output().stdout.clone()).unwrap();
    let report: serde_json::Value = serde_json::from_str(&stdout).expect("probe emits JSON");

    // All 9 corpus files parse and re-serialize.
    assert_eq!(report["fixtures"], 9);
    assert_eq!(report["roundTripped"], 9);
    // Slugs (unicode-normalization + the slug transform) ran.
    assert_eq!(report["slug"], "cafe-deja-vu");
    // Markdown parser (regex) extracted the probe's built-in snippet.
    assert_eq!(report["markdownTasks"], 2);
    // toml_edit read-modify-write preserved layout and applied the edit.
    assert_eq!(report["toml"], "[probe]\n# layout survives\nruns = 2\n");
    // time formatted an XSD-lexical timestamp (shape only; value varies).
    let ts = report["timestamp"].as_str().unwrap();
    assert_eq!(ts.len(), "2026-07-18T00:00:00".len());
    assert_eq!(&ts[4..5], "-");
}

#[test]
fn probe_is_hidden_from_help() {
    let out = Command::cargo_bin("shipsmooth").unwrap().arg("--help").assert().success();
    let help = String::from_utf8(out.get_output().stdout.clone()).unwrap();
    assert!(!help.contains("probe"), "probe must stay hidden: {help}");
}

#[test]
fn probe_fails_cleanly_on_a_missing_dir() {
    Command::cargo_bin("shipsmooth")
        .unwrap()
        .args(["probe", "--dir", "/nonexistent"])
        .assert()
        .failure()
        .code(1);
}
