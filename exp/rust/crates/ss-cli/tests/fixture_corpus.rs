//! plan-106 Task 1: completeness check for the store-resolution golden corpus.
//!
//! The corpus under fixtures/transcripts/store/ is the spec every later task is
//! checked against, so this test pins that generate.sh captured the RIGHT thing
//! for every branch of the plan-85 resolution table: one directory per
//! scenario, the expected classification in the JSON transcript, empty stderr
//! (`store info` is informational — stdout only), exit 0, and the
//! shipsmooth.toml that produced the classification. It is regeneration-proof:
//! it asserts structure and stable tokens, never absolute paths.

use std::path::{Path, PathBuf};

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

impl Scenario {
    /// Assert this branch was captured correctly under `corpus`.
    fn assert_captured(&self, corpus: &Path) {
        let dir = corpus.join(self.name);
        assert!(dir.is_dir(), "missing corpus scenario directory: {}", dir.display());

        // `store info` always exits 0 — settled, undecided, and unresolvable
        // are all valid things to report.
        assert_eq!(read(&dir, "info.exit").trim(), "exit=0", "scenario {}", self.name);
        assert_eq!(read(&dir, "info-json.exit").trim(), "exit=0", "scenario {}", self.name);

        // stdout/stderr discipline: informational output only, stderr empty.
        assert_eq!(read(&dir, "info.err"), "", "scenario {}: stderr must be empty", self.name);
        assert_eq!(read(&dir, "info-json.err"), "", "scenario {}: stderr must be empty", self.name);
        assert!(!read(&dir, "info.out").is_empty(), "scenario {}: text transcript empty", self.name);

        // The JSON transcript is one line and classifies as this branch expects.
        let json = self.parsed_json_transcript(&dir);
        assert_eq!(json["status"], self.status, "scenario {}", self.name);
        if let Some((field, value)) = self.token {
            assert_eq!(json[field], value, "scenario {}", self.name);
        }

        // The config that produced the classification is part of the spec.
        assert_eq!(
            dir.join("shipsmooth.toml").is_file(),
            self.has_toml,
            "scenario {}: shipsmooth.toml capture mismatch",
            self.name
        );
    }

    fn parsed_json_transcript(&self, dir: &Path) -> serde_json::Value {
        let line = read(dir, "info-json.out");
        assert!(
            line.ends_with('\n') && line.lines().count() == 1,
            "scenario {}: expected a single JSON line",
            self.name
        );
        serde_json::from_str(&line)
            .unwrap_or_else(|e| panic!("scenario {}: unparseable JSON transcript: {e}", self.name))
    }
}

/// The plan-85 branch table, as listed in plan-106 Task 1.
const SCENARIOS: &[Scenario] = &[
    Scenario {
        name: "clean-first-run",
        status: "needs-decision",
        token: Some(("situation", "clean-first-run")),
        has_toml: false,
    },
    // plan-87 leniency: a 0-byte config is "no usable config", never a wedge.
    Scenario {
        name: "empty-config",
        status: "needs-decision",
        token: Some(("situation", "clean-first-run")),
        has_toml: true,
    },
    Scenario {
        name: "settled-same-repo",
        status: "ready",
        token: Some(("storageType", "same-repo")),
        has_toml: true,
    },
    Scenario {
        name: "settled-separate-dir",
        status: "ready",
        token: Some(("storageType", "separate-dir")),
        has_toml: true,
    },
    Scenario {
        name: "in-repo-not-set-up",
        status: "needs-decision",
        token: Some(("situation", "in-repo-not-set-up")),
        has_toml: true,
    },
    Scenario {
        name: "config-dir-missing",
        status: "needs-decision",
        token: Some(("situation", "config-dir-missing")),
        has_toml: true,
    },
    Scenario {
        name: "malformed-missing-type",
        status: "unresolvable",
        token: Some(("reason", "MALFORMED_CONFIG_ENTRY")),
        has_toml: true,
    },
    Scenario {
        name: "malformed-bad-type",
        status: "unresolvable",
        token: Some(("reason", "MALFORMED_CONFIG_ENTRY")),
        has_toml: true,
    },
    Scenario {
        name: "malformed-same-repo-with-root",
        status: "unresolvable",
        token: Some(("reason", "MALFORMED_CONFIG_ENTRY")),
        has_toml: true,
    },
    Scenario {
        name: "legacy-agents-tree",
        status: "unresolvable",
        token: Some(("reason", "LEGACY_AGENTS_TREE")),
        has_toml: false,
    },
];

fn store_corpus_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/transcripts/store")
}

fn read(dir: &Path, file: &str) -> String {
    std::fs::read_to_string(dir.join(file))
        .unwrap_or_else(|e| panic!("missing corpus file {}/{file}: {e}", dir.display()))
}

#[test]
fn store_resolution_corpus_covers_the_branch_table() {
    let corpus = store_corpus_dir();
    for scenario in SCENARIOS {
        scenario.assert_captured(&corpus);
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
