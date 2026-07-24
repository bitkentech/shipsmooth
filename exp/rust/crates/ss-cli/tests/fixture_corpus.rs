//! plan-106 Task 1: completeness check for the store-resolution golden corpus.
//!
//! The corpus under fixtures/transcripts/store/ is the spec every later task is
//! checked against, so this test pins that generate.sh captured the RIGHT thing
//! for every branch of the plan-85 resolution table: one directory per
//! scenario, the expected classification in the JSON transcript, empty stderr
//! (`store info` is informational — stdout only), exit 0, and the
//! shipsmooth.toml that produced the classification. It is regeneration-proof:
//! it asserts structure and stable tokens, never absolute paths.

use std::path::PathBuf;

/// One branch of the resolution table: directory name, the `status` the JSON
/// transcript must report, the situation/reason token pinning the exact branch,
/// and whether a shipsmooth.toml drove the classification (and so must be
/// captured alongside).
struct Scenario {
    name: &'static str,
    status: &'static str,
    token: Option<(&'static str, &'static str)>,
    has_toml: bool,
}

const fn scenario(
    name: &'static str,
    status: &'static str,
    token: Option<(&'static str, &'static str)>,
    has_toml: bool,
) -> Scenario {
    Scenario { name, status, token, has_toml }
}

/// The plan-85 branch table, as listed in plan-106 Task 1.
const SCENARIOS: &[Scenario] = &[
    scenario("clean-first-run", "needs-decision", Some(("situation", "clean-first-run")), false),
    // plan-87 leniency: a 0-byte config is "no usable config", never a wedge.
    scenario("empty-config", "needs-decision", Some(("situation", "clean-first-run")), true),
    scenario("settled-same-repo", "ready", Some(("storageType", "same-repo")), true),
    scenario("settled-separate-dir", "ready", Some(("storageType", "separate-dir")), true),
    scenario("in-repo-not-set-up", "needs-decision", Some(("situation", "in-repo-not-set-up")), true),
    scenario("config-dir-missing", "needs-decision", Some(("situation", "config-dir-missing")), true),
    scenario("malformed-missing-type", "unresolvable", Some(("reason", "MALFORMED_CONFIG_ENTRY")), true),
    scenario("malformed-bad-type", "unresolvable", Some(("reason", "MALFORMED_CONFIG_ENTRY")), true),
    scenario(
        "malformed-same-repo-with-root",
        "unresolvable",
        Some(("reason", "MALFORMED_CONFIG_ENTRY")),
        true,
    ),
    scenario("legacy-agents-tree", "unresolvable", Some(("reason", "LEGACY_AGENTS_TREE")), false),
];

fn store_corpus_dir() -> PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/transcripts/store")
}

fn read(dir: &PathBuf, file: &str) -> String {
    std::fs::read_to_string(dir.join(file))
        .unwrap_or_else(|e| panic!("missing corpus file {}/{file}: {e}", dir.display()))
}

#[test]
fn store_resolution_corpus_covers_the_branch_table() {
    for s in SCENARIOS {
        let dir = store_corpus_dir().join(s.name);
        assert!(dir.is_dir(), "missing corpus scenario directory: {}", dir.display());

        // `store info` always exits 0 — settled, undecided, and unresolvable
        // are all valid things to report.
        assert_eq!(read(&dir, "info.exit").trim(), "exit=0", "scenario {}", s.name);
        assert_eq!(read(&dir, "info-json.exit").trim(), "exit=0", "scenario {}", s.name);

        // stdout/stderr discipline: informational output only, stderr empty.
        assert_eq!(read(&dir, "info.err"), "", "scenario {}: stderr must be empty", s.name);
        assert_eq!(read(&dir, "info-json.err"), "", "scenario {}: stderr must be empty", s.name);
        assert!(!read(&dir, "info.out").is_empty(), "scenario {}: text transcript empty", s.name);

        // The JSON transcript is one line and classifies as this branch expects.
        let json_line = read(&dir, "info-json.out");
        assert!(json_line.ends_with('\n') && json_line.lines().count() == 1,
            "scenario {}: expected a single JSON line", s.name);
        let json: serde_json::Value = serde_json::from_str(&json_line)
            .unwrap_or_else(|e| panic!("scenario {}: unparseable JSON transcript: {e}", s.name));
        assert_eq!(json["status"], s.status, "scenario {}", s.name);
        if let Some((field, value)) = s.token {
            assert_eq!(json[field], value, "scenario {}", s.name);
        }

        // The config that produced the classification is part of the spec.
        assert_eq!(dir.join("shipsmooth.toml").is_file(), s.has_toml,
            "scenario {}: shipsmooth.toml capture mismatch", s.name);
    }
}

/// The two init-driven scenarios also capture the `store init` transcript, the
/// spec for the init leaf (Task 7): ready shape on stdout, exit 0, stderr empty.
#[test]
fn init_transcripts_are_captured_for_the_settled_scenarios() {
    for name in ["settled-same-repo", "settled-separate-dir"] {
        let dir = store_corpus_dir().join(name);
        assert_eq!(read(&dir, "init.exit").trim(), "exit=0", "scenario {name}");
        assert_eq!(read(&dir, "init.err"), "", "scenario {name}: stderr must be empty");
        let json: serde_json::Value = serde_json::from_str(&read(&dir, "init.out"))
            .unwrap_or_else(|e| panic!("scenario {name}: unparseable init transcript: {e}"));
        assert_eq!(json["status"], "ready", "scenario {name}");
    }
}
