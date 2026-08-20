//! The `task` noun group: manage individual tasks within a plan.
//!
//! Port of the Java `io.bitken.ss.cli.task` package. Every leaf is state-
//! dependent — `main`'s resolve gate constructs the `TaskStore`/`GitTags`
//! this module's leaves need before `run` is ever called.

mod add;
mod comment;
mod status;

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
    /// Add a comment to a task.
    Comment {
        /// Plan number
        #[arg(long, value_name = "PLAN_NUMBER")]
        plan: u32,
        /// Task ID (integer)
        #[arg(long, value_name = "TASK_ID")]
        task: u32,
        /// The comment text
        #[arg(long, value_name = "MESSAGE")]
        message: String,
    },
    /// Update the status of a task.
    Status {
        /// Plan number
        #[arg(long, value_name = "PLAN_NUMBER")]
        plan: u32,
        /// Task ID (integer)
        #[arg(long, value_name = "TASK_ID")]
        task: u32,
        /// New task status: pending, in-progress, de-risked, agent-coded, closed, needs-triage, abandoned
        #[arg(long, value_name = "STATUS")]
        status: String,
    },
}

/// Dispatch a `task` leaf against the already-constructed, settled gateways.
pub fn run(command: &TaskCommand, store: &ss_core::gw::TaskStore, git_tags: &ss_core::gw::GitTags) -> i32 {
    match command {
        TaskCommand::Add { plan, name, risk, depends_on } => report(add::run(
            store,
            git_tags,
            *plan,
            name,
            risk.as_deref().unwrap_or(""),
            depends_on.as_deref().unwrap_or(""),
        )),
        TaskCommand::Comment { plan, task, message } => {
            report(comment::run(store, *plan, *task, message))
        }
        // The one leaf that owns its own exit code: an invalid --status is
        // rejected before TaskStore is touched, with Java's own message and
        // exit 2 rather than the generic shape below.
        TaskCommand::Status { plan, task, status } => match status::run(store, *plan, *task, status) {
            Ok(message) => {
                println!("{message}");
                0
            }
            Err((code, message)) => {
                eprintln!("{message}");
                code
            }
        },
    }
}

/// The shape every leaf but `status` shares: the success line on stdout, or
/// the CLI's generic `shipsmooth: {message}` failure on stderr with exit 1.
fn report(outcome: Result<String, String>) -> i32 {
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
