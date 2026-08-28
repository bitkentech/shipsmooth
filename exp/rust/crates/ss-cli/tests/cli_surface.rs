//! plan-110 preamble: end-to-end tests for the CLI-surface parity gaps the
//! harness never invoked.
//!
//! Every expectation below was captured by running the Java CLI 0.3.36
//! (`shipsmooth`, `shipsmooth store`, `shipsmooth --version`,
//! `shipsmooth store init --type recreate` on a clean first run) rather than
//! read off its source — the plan-109 lesson about checking a claim against a
//! running binary. Committed red before any of plan-110's fixes exist.
//!
//! Help *body* text is a deliberate non-contract (picocli and clap lay out
//! usage differently, and skills invoke commands rather than help), so these
//! assert the stream, the exit code and the leading "Usage:" marker only.

use assert_cmd::Command;
use std::path::Path;

/// A throwaway git project plus a redirected config home, so no test can read
/// or write the real ~/.config/shipsmooth/shipsmooth.toml.
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
}

fn git(dir: &Path, args: &[&str]) {
    let status = std::process::Command::new("git").args(args).current_dir(dir).status().unwrap();
    assert!(status.success(), "git {args:?} failed in {dir:?}");
}

/// Spec, captured from Java 0.3.36:
///
/// ```text
/// $ shipsmooth            -> exit 2, stdout empty, stderr "Missing required subcommand\nUsage: ..."
/// $ shipsmooth store      -> exit 0, stdout empty, stderr "Usage: shipsmooth store ..."
/// $ shipsmooth plan       -> exit 0, stdout empty, stderr "Usage: shipsmooth plan ..."
/// $ shipsmooth task       -> exit 0, stdout empty, stderr "Usage: shipsmooth task ..."
/// ```
///
/// ```text
/// $ shipsmooth --version -> exit 0, stdout "0.3.36\n"
/// ```
///
/// The asymmetry (root 2, groups 0) is Java's real behaviour — the groups are
/// pinned Java-side by `GroupedCommandTreeTest`. Port it as-is rather than
/// normalising the two into agreement.
///
/// The version *value* differs by design — the Cargo workspace version is
/// pinned at 0.3.34 and deliberately not synced to Java releases (plan-106) —
/// so this asserts the shape, not the digits.
#[test]
fn the_clap_root_surface_matches_the_java_cli() {
    let fx = Fixture::new();

    let root = fx.shipsmooth(&[]).output().unwrap();
    assert_eq!(root.status.code(), Some(2), "bare root must exit 2");
    assert!(root.stdout.is_empty(), "bare root must print nothing on stdout");
    let root_err = String::from_utf8_lossy(&root.stderr);
    assert!(
        root_err.contains("Usage: shipsmooth"),
        "bare root must print usage on stderr, got: {root_err:?}"
    );

    for group in ["store", "plan", "task"] {
        let out = fx.shipsmooth(&[group]).output().unwrap();
        assert_eq!(out.status.code(), Some(0), "bare `{group}` must exit 0");
        assert!(out.stdout.is_empty(), "bare `{group}` must print nothing on stdout");
        let err = String::from_utf8_lossy(&out.stderr);
        assert!(
            err.contains(&format!("Usage: shipsmooth {group}")),
            "bare `{group}` must print its usage on stderr, got: {err:?}"
        );
    }

    let version = fx.shipsmooth(&["--version"]).output().unwrap();
    assert_eq!(version.status.code(), Some(0), "--version must exit 0");
    let printed = String::from_utf8_lossy(&version.stdout);
    let line = printed.trim_end_matches('\n');
    assert!(
        !line.starts_with("shipsmooth "),
        "--version must not prefix the binary name, got: {line:?}"
    );
    assert_eq!(line, env!("CARGO_PKG_VERSION"), "--version must print the bare workspace version");
}

/// Spec, captured from Java 0.3.36 on a clean first run:
///
/// ```text
/// $ shipsmooth store init --type recreate
/// shipsmooth: --type recreate is not valid for the current situation (CLEAN_FIRST_RUN)   [stderr, exit 1]
/// ```
///
/// `recreate` is a real type token, just not one this situation offers, so the
/// message names the situation. Java renders the enum name; Rust must not leak
/// its own CamelCase spelling through `{:?}`.
#[test]
fn an_off_menu_init_names_the_situation_the_java_way() {
    let fx = Fixture::new();

    let out = fx.shipsmooth(&["store", "init", "--type", "recreate"]).output().unwrap();

    assert_eq!(out.status.code(), Some(1), "an off-menu --type must exit 1");
    assert!(out.stdout.is_empty(), "the refusal belongs on stderr only");
    assert_eq!(
        String::from_utf8_lossy(&out.stderr).trim_end_matches('\n'),
        "shipsmooth: --type recreate is not valid for the current situation (CLEAN_FIRST_RUN)"
    );
}
