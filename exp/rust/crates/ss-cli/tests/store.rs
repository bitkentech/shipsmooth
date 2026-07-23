//! plan-106 preamble: end-to-end integration tests for the `store` noun group.
//!
//! These pin the two skill-facing contracts byte-exactly against the Java CLI
//! transcripts in fixtures/transcripts/ (store-info-unsettled.json,
//! store-init-same-repo.json, store-info-ready.json): the clean-first-run
//! needs-decision gate, and the init -> info ready round trip. Committed red
//! before any store implementation exists.

use assert_cmd::Command;
use std::path::Path;

/// A throwaway project repo plus a redirected config home, mirroring
/// fixtures/generate.sh. XDG_CONFIG_HOME points at an empty temp dir so no
/// test ever reads or writes the real ~/.config/shipsmooth/shipsmooth.toml.
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
        git(&repo, &["init", "-q", "."]);
        git(&repo, &[
            "-c",
            "user.email=fixture@example.com",
            "-c",
            "user.name=Fixture",
            "commit",
            "-q",
            "--allow-empty",
            "-m",
            "seed",
        ]);
        Fixture { _work: work, repo, config_home }
    }

    fn shipsmooth(&self, args: &[&str]) -> Command {
        let mut cmd = Command::cargo_bin("shipsmooth").unwrap();
        cmd.args(args).current_dir(&self.repo).env("XDG_CONFIG_HOME", &self.config_home);
        cmd
    }

    fn repo_str(&self) -> &str {
        self.repo.to_str().unwrap()
    }
}

fn git(dir: &Path, args: &[&str]) {
    let status = std::process::Command::new("git").args(args).current_dir(dir).status().unwrap();
    assert!(status.success(), "git {args:?} failed in {dir:?}");
}

/// Spec: fixtures/transcripts/store-info-unsettled.json + .exit — a clean
/// first run reports the needs-decision gate on stdout and exits 0 (info is
/// informational; only the resolve gate on state-dependent commands exits 10).
#[test]
fn store_info_json_on_clean_first_run_reports_needs_decision() {
    let fx = Fixture::new();
    let repo = fx.repo_str();
    let expected = format!(
        concat!(
            "{{\"status\":\"needs-decision\",\"situation\":\"clean-first-run\",",
            "\"message\":\"Where should shipsmooth store all its information for this project?\",",
            "\"prompt\":\"Where should shipsmooth store all its information for this project?\\n",
            "  Recommended — a separate folder next to this repo: {repo}-shipsmooth\\n",
            "  Alternative — inside this repo: {repo}/.shipsmooth\\n\\n",
            "You can also enter a different folder path.\",",
            "\"options\":[",
            "{{\"choice\":\"separate-dir\",\"proposedPath\":\"{repo}-shipsmooth\",\"recommended\":true}},",
            "{{\"choice\":\"same-repo\",\"proposedPath\":\"{repo}/.shipsmooth\",\"recommended\":false}}",
            "]}}\n"
        ),
        repo = repo
    );
    fx.shipsmooth(&["store", "info", "--json"])
        .assert()
        .code(0)
        .stdout(expected)
        .stderr("");
}

/// Spec: fixtures/transcripts/store-init-same-repo.json + store-info-ready.json —
/// `store init --type same-repo --json` settles the store and prints the ready
/// shape, and a subsequent `store info --json` prints the identical shape.
#[test]
fn store_init_same_repo_then_info_round_trips_the_ready_shape() {
    let fx = Fixture::new();
    let repo = fx.repo_str();
    let ready = format!(
        concat!(
            "{{\"status\":\"ready\",\"storageType\":\"same-repo\",",
            "\"stateRoot\":\"{repo}\",\"plansDir\":\"{repo}/.shipsmooth/plans\"}}\n"
        ),
        repo = repo
    );
    fx.shipsmooth(&["store", "init", "--type", "same-repo", "--json"])
        .assert()
        .code(0)
        .stdout(ready.clone())
        .stderr("");
    fx.shipsmooth(&["store", "info", "--json"]).assert().code(0).stdout(ready).stderr("");
}
