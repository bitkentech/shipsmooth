//! shipsmooth CLI (Rust) — walking skeleton (plan-102 Task 2).
//!
//! Only the frame exists at this stage: clap root with version, and the
//! run() -> exit-code shape the resolve gate needs (exit codes 10/11 are
//! emitted by dispatch, not by panicking handlers). Subcommands land with
//! their packages in the follow-up plan.

mod ds;
mod probe;
mod project;
mod resolution_json;
mod store;

use clap::Parser;

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
}

fn run(args: impl IntoIterator<Item = String>) -> i32 {
    match Cli::try_parse_from(args) {
        Ok(Cli { command: Some(Command::Probe(args)), .. }) => probe::run(&args),
        Ok(Cli { command: Some(Command::Store { command }), .. }) => store::run(&command),
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
}
