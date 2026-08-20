//! The `plan` noun group: create, inspect and tag plans.
//!
//! Port of the Java `io.bitken.ss.cli.plan` package. Every leaf is state-
//! dependent, so `main`'s resolve gate (plan-108) constructs the gateways
//! before `run` is ever called — no new wiring was needed for this group.
//!
//! Note the stream convention differs from `store`/`task`: most plan leaves
//! print their `ERROR: …` lines to **stdout**, matching Java. `init` is the
//! exception and uses stderr. Ported as observed, not as expected.

mod init;
mod quick;

use crate::LeafContext;

#[derive(clap::Subcommand)]
pub enum PlanCommand {
    /// Initialize task tracking XML for a plan
    Init {
        /// Plan number
        #[arg(long, value_name = "PLAN_NUMBER")]
        plan: u32,
        /// Path to the plan markdown file
        #[arg(long = "tasks-from", value_name = "<Path to Markdown file>")]
        tasks_from: String,
    },
    /// Quick start mode: Derive plan number, create a branch, write a stub plan file. No git commit.
    Quick {
        /// Short plan description (used for the branch slug)
        #[arg(long, value_name = "TEXT")]
        desc: String,
    },
}

/// Dispatch a `plan` leaf against the already-constructed, settled gateways.
pub fn run(command: &PlanCommand, cx: &LeafContext) -> i32 {
    match command {
        PlanCommand::Init { plan, tasks_from } => {
            init::run(&cx.store, &cx.git_tags, *plan, tasks_from)
        }
        PlanCommand::Quick { desc } => quick::run(cx, desc),
    }
}
