//! shipsmooth CLI (Rust) — clap root, dispatch, and the resolve gate.
//!
//! `store`/`probe` are state-independent and dispatch unconditionally;
//! `task` is state-dependent, so `dispatch_task` resolves the project's data
//! store exactly once and runs it through `gate` before constructing any
//! service, mirroring the Java `Shipsmooth.execute()` contract (exit codes
//! 10/11, resolution JSON on stdout) with static classification in place of
//! Java's lazy-`Provider` exception handler (see 02-cli.md "The resolve gate").

mod ds;
mod probe;
mod project;
mod resolution_json;
mod store;
mod task;

use clap::Parser;
use ss_core::conf::{ResolvedStateRoot, ShipsmoothDataLocator};
use ss_core::gw::{GitTags, TaskStore};

use ds::resolution::DataStoreResolution;
use ds::resolver::ProjectDataStoreResolver;
use project::ProjectContext;

/// Exit codes the skill branches on when startup cannot settle the store.
pub const EXIT_NEEDS_DECISION: i32 = 10;
pub const EXIT_UNRESOLVABLE: i32 = 11;

#[derive(Parser)]
#[command(
    name = "shipsmooth",
    version,
    about = "CLI to manage plans and tasks for shipsmooth"
)]
struct Cli {
    /// Enable experimental subcommands.
    #[arg(long = "enable-experimental", global = true, hide = !cfg!(feature = "experimental"))]
    enable_experimental: bool,

    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(clap::Subcommand)]
enum Command {
    /// Footprint probe (plan-102 Task 6) — not part of the CLI surface.
    #[command(hide = true)]
    Probe(probe::ProbeArgs),
    /// Manage where this project's shipsmooth state lives.
    Store {
        #[command(subcommand)]
        command: store::StoreCommand,
    },
    /// Manage individual tasks within a plan and record their progress.
    Task {
        #[command(subcommand)]
        command: task::TaskCommand,
    },
}

fn run(args: impl IntoIterator<Item = String>) -> i32 {
    match Cli::try_parse_from(args) {
        Ok(Cli { command: Some(command), .. }) => dispatch(command),
        Ok(_cli) => 0,
        Err(e) => {
            // clap renders --help/--version through the Err path with exit 0.
            let _ = e.print();
            if e.use_stderr() {
                2
            } else {
                0
            }
        }
    }
}

/// State-independent commands (`store`, `probe`, `--help`/`--version`) run
/// unconditionally. A state-dependent command (`task`) resolves the store
/// once, gates on an unsettled project, and only then is handed the
/// constructed gateways — see `gate` and `dispatch_task`.
fn dispatch(command: Command) -> i32 {
    match command {
        Command::Probe(args) => probe::run(&args),
        Command::Store { command } => store::run(&command),
        Command::Task { command } => dispatch_task(&command),
    }
}

/// Resolve this invocation's project context and data store exactly once,
/// gate on it, and dispatch to the `task` leaf only once it is `Settled`.
fn dispatch_task(command: &task::TaskCommand) -> i32 {
    let cwd = std::env::current_dir().unwrap_or_else(|_| std::path::Path::new(".").to_path_buf());
    let project = ProjectContext::from_dir(&cwd);
    let resolver = ProjectDataStoreResolver::new(ds::config_file::locate());
    let resolution = resolver.resolve(&project.repo_root, project.remote_url());

    match gate(resolution) {
        GateOutcome::Exit(code) => code,
        GateOutcome::Proceed(store) => {
            let token = ResolvedStateRoot::of(store.state_root()).expect("settled state root must be accessible");
            let locator = ShipsmoothDataLocator::new(&project.repo_root, token)
                .expect("settled repo root must be accessible");
            let task_store = TaskStore::new(locator);
            let git_tags = GitTags::new(&project.repo_root);
            task::run(command, &task_store, &git_tags)
        }
    }
}

/// The result of running a resolution through the resolve gate: either the
/// exit code for an unsettled project (its JSON already printed), or the
/// settled store to proceed with.
enum GateOutcome {
    Exit(i32),
    Proceed(ds::store::ProjectDataStore),
}

/// The resolve gate: for an unsettled project, print the resolution JSON and
/// carry the exit code the skill branches on; for a settled one, hand back
/// the store to construct services from. Consumes `resolution` (rather than
/// re-matching it after `Settled`) so there is no unreachable arm to cover.
fn gate(resolution: DataStoreResolution) -> GateOutcome {
    match resolution {
        DataStoreResolution::Settled(store) => GateOutcome::Proceed(store),
        DataStoreResolution::NeedsDecision(needs) => {
            println!("{}", resolution_json::needs_decision(&needs));
            GateOutcome::Exit(EXIT_NEEDS_DECISION)
        }
        DataStoreResolution::Unresolvable(bad) => {
            println!("{}", resolution_json::unresolvable(&bad));
            GateOutcome::Exit(EXIT_UNRESOLVABLE)
        }
    }
}

fn main() {
    std::process::exit(run(std::env::args()));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_matches_workspace_version() {
        // The workspace version is the single source of truth (Build.VERSION).
        assert_eq!(env!("CARGO_PKG_VERSION"), "0.3.34");
    }

    #[test]
    fn bare_invocation_parses() {
        assert_eq!(run(["shipsmooth".to_string()]), 0);
    }

    #[test]
    fn store_info_accepts_the_short_json_flag() {
        // Java's InfoTest exercises "-j" through picocli; here the flag is
        // declared in clap, so pin the short alias at parse level.
        let cli = Cli::try_parse_from(["shipsmooth", "store", "info", "-j"]).unwrap();
        match cli.command {
            Some(Command::Store { command: store::StoreCommand::Info { json } }) => assert!(json),
            _ => panic!("expected store info"),
        }
    }

    // ── Task 1 de-risk: the resolve gate (plan-108) ─────────────────────────
    // Core-logic tests for `gate` in isolation from any concrete command,
    // since clap's static command tree means only `task` is ever
    // state-dependent — these pin the mechanism the integration tests
    // (tests/task.rs) exercise end-to-end through the real CLI binary.

    use ds::resolution::{
        Choice, DecisionOption, NeedsDecision, UndecidableSituation, Unresolvable, UnresolvableReason,
    };
    use ds::store::ProjectDataStore;

    #[test]
    fn gate_lets_a_settled_resolution_through() {
        let resolution =
            DataStoreResolution::Settled(ProjectDataStore::InRepo { repo_root: "/proj".into() });
        match gate(resolution) {
            GateOutcome::Proceed(ProjectDataStore::InRepo { repo_root }) => {
                assert_eq!(repo_root, std::path::Path::new("/proj"))
            }
            _ => panic!("expected Proceed"),
        }
    }

    #[test]
    fn gate_prints_needs_decision_json_and_returns_exit_10() {
        let resolution = DataStoreResolution::NeedsDecision(NeedsDecision {
            situation: UndecidableSituation::CleanFirstRun,
            options: vec![DecisionOption {
                choice: Choice::InRepo,
                proposed_path: "/proj/.shipsmooth".into(),
                recommended: true,
            }],
        });
        assert!(matches!(gate(resolution), GateOutcome::Exit(EXIT_NEEDS_DECISION)));
    }

    #[test]
    fn gate_prints_unresolvable_json_and_returns_exit_11() {
        let resolution =
            DataStoreResolution::Unresolvable(Unresolvable::of(UnresolvableReason::LegacyAgentsTree));
        assert!(matches!(gate(resolution), GateOutcome::Exit(EXIT_UNRESOLVABLE)));
    }
}
