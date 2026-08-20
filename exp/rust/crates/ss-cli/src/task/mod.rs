//! The `task` noun group: manage individual tasks within a plan.
//!
//! Port of the Java `io.bitken.ss.cli.task` package. Every leaf is state-
//! dependent — `main`'s resolve gate constructs the `TaskStore`/`GitTags`
//! this module's leaves need before `run` is ever called.

mod add;

#[derive(clap::Subcommand)]
pub enum TaskCommand {
    /// Append a new task to an existing plan.
    Add {
        /// Plan number
        #[arg(long, value_name = "PLAN_NUMBER")]
        plan: u32,
        /// Task name
        #[arg(long, value_name = "TEXT")]
        name: String,
        /// Risk level (high|medium|low)
        #[arg(long, value_name = "RISK")]
        risk: Option<String>,
        /// Comma-separated task ids this task depends on (e.g. 1,3)
        #[arg(long = "depends-on", value_name = "IDS")]
        depends_on: Option<String>,
    },
}

/// Dispatch a `task` leaf against the already-constructed, settled gateways.
pub fn run(command: &TaskCommand, store: &ss_core::gw::TaskStore, git_tags: &ss_core::gw::GitTags) -> i32 {
    match command {
        TaskCommand::Add { plan, name, risk, depends_on } => {
            let outcome = add::run(
                store,
                git_tags,
                *plan,
                name,
                risk.as_deref().unwrap_or(""),
                depends_on.as_deref().unwrap_or(""),
            );
            match outcome {
                Ok(message) => {
                    println!("{message}");
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
