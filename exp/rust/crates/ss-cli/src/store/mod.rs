//! The `store` noun group: where a project's shipsmooth state lives.
//!
//! Port of the Java `io.bitken.ss.cli.store` package. `info` reports the
//! resolved location on demand (the `init` leaf lands with Task 7). All
//! informational output goes to stdout.

mod info;
mod state_report;

use std::path::Path;

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
}

/// Dispatch a `store` leaf: derive the project context from the CWD (Java's
/// `main` binding — repo root via git, origin URL if present), resolve, print.
pub fn run(command: &StoreCommand) -> i32 {
    let cwd = std::env::current_dir().unwrap_or_else(|_| Path::new(".").to_path_buf());
    let repo_root = project::repo_root(&cwd);
    let remote_url = project::remote_url(&repo_root);
    let resolver = ProjectDataStoreResolver::new(ds::config_file::locate());

    match command {
        StoreCommand::Info { json } => {
            println!("{}", info::report(&resolver, &repo_root, remote_url.as_deref(), *json));
            0
        }
    }
}
