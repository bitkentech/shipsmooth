//! The `store` noun group: where a project's shipsmooth state lives.
//!
//! Port of the Java `io.bitken.ss.cli.store` package. `info` reports the
//! resolved location on demand; `init` acts on a first-run choice. All
//! informational output goes to stdout; failures go to stderr with exit 1.

mod info;
mod init;
mod state_report;

use std::path::Path;

use crate::ds::config_writer::ConfigWriter;
use crate::ds::resolver::ProjectDataStoreResolver;
use crate::{ds, project};

#[derive(clap::Subcommand)]
pub enum StoreCommand {
    /// Report where this project's shipsmooth state lives.
    Info {
        /// Emit a single machine-readable JSON line instead of text.
        #[arg(long, short = 'j')]
        json: bool,
    },
    /// Act on a first-run choice: create the chosen state location and record it.
    Init {
        /// same-repo | separate-dir | recreate
        // A plain string, not a value_enum: the Java command owns the
        // unknown-type message and its exit-1 shape.
        #[arg(long = "type", value_name = "TYPE")]
        type_arg: String,
        /// State directory (for external/recreate).
        #[arg(long, value_name = "PATH")]
        path: Option<String>,
        /// Emit the resulting state location as a machine-readable JSON line.
        #[arg(long, short = 'j')]
        json: bool,
    },
}

/// Dispatch a `store` leaf: derive the project context from the CWD (Java's
/// `main` binding — repo root via git, origin URL if present), resolve, print.
pub fn run(command: &StoreCommand) -> i32 {
    let cwd = std::env::current_dir().unwrap_or_else(|_| Path::new(".").to_path_buf());
    let repo_root = project::repo_root(&cwd);
    let remote_url = project::remote_url(&repo_root);
    let config_file = ds::config_file::locate();
    let resolver = ProjectDataStoreResolver::new(config_file.clone());

    match command {
        StoreCommand::Info { json } => {
            println!("{}", info::report(&resolver, &repo_root, remote_url.as_deref(), *json));
            0
        }
        StoreCommand::Init { type_arg, path, json } => {
            // Single resolution per invocation — the one source of truth for
            // this run (the Java main computes it once and binds it).
            let resolution = resolver.resolve(&repo_root, remote_url.as_deref());
            let outcome = init::run(
                &resolver,
                &ConfigWriter::new(config_file),
                &repo_root,
                remote_url.as_deref(),
                resolution,
                type_arg,
                path.as_deref(),
                *json,
            );
            match outcome {
                Ok(report) => {
                    println!("{report}");
                    0
                }
                Err(message) => {
                    eprintln!("shipsmooth: {message}");
                    1
                }
            }
        }
    }
}
