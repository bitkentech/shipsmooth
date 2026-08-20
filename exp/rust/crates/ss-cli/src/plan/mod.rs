//! The `plan` noun group: create, inspect and tag plans.
//!
//! Port of the Java `io.bitken.ss.cli.plan` package. Every leaf is state-
//! dependent, so `main`'s resolve gate (plan-108) constructs the gateways
//! before `run` is ever called — no new wiring was needed for this group.
//!
//! Note the stream convention differs from `store`/`task`: most plan leaves
//! print their `ERROR: …` lines to **stdout**, matching Java. `init` is the
//! exception and uses stderr. Ported as observed, not as expected.

mod branch;
mod init;
mod preflight;
mod quick;
mod show;
mod tag;

use ss_core::gw::GitState;

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
    /// Create a plan version/complete/abandoned tag.
    Tag {
        /// Plan number
        #[arg(long, value_name = "PLAN_NUMBER")]
        plan: u32,
        /// Tag kind: version, complete, abandoned
        #[arg(long, value_name = "KIND")]
        kind: String,
    },
    /// Verify plan preconditions before Phase 2.
    Preflight {
        /// Plan number
        #[arg(long, value_name = "PLAN_NUMBER")]
        plan: u32,
    },
    /// Create a task branch locally and print the push line.
    Branch {
        /// Issue ID for the branch (used in the slug; defaults to the plan number)
        #[arg(long, value_name = "ISSUE_ID")]
        issue: Option<String>,
        /// Plan number (used for the slug when --issue is absent)
        #[arg(long, value_name = "PLAN_NUMBER")]
        plan: Option<u32>,
        /// Short branch description
        #[arg(long, value_name = "TEXT")]
        desc: String,
    },
    /// Show plan tasks.
    Show {
        /// Plan number
        #[arg(long, value_name = "PLAN_NUMBER")]
        plan: u32,
    },
    /// Session-resume pre-flight: task state check.
    Resume {
        /// Plan number
        #[arg(long, value_name = "PLAN_NUMBER")]
        plan: u32,
    },
    /// Add a project update.
    Update {
        /// Plan number
        #[arg(long, value_name = "PLAN_NUMBER")]
        plan: u32,
        /// New plan status: active, complete, abandoned, in-review
        #[arg(long, value_name = "STATUS")]
        status: Option<String>,
        /// Mark the plan blocked (major deviation)
        #[arg(long)]
        blocked: bool,
        /// Update message
        #[arg(long, value_name = "TEXT")]
        message: Option<String>,
    },
}

/// Dispatch a `plan` leaf against the already-constructed, settled gateways.
pub fn run(command: &PlanCommand, cx: &LeafContext) -> i32 {
    match command {
        PlanCommand::Init { plan, tasks_from } => {
            init::run(&cx.store, &cx.git_tags, *plan, tasks_from)
        }
        PlanCommand::Quick { desc } => quick::run(cx, desc),
        PlanCommand::Tag { plan, kind } => tag::run(&cx.git_tags, *plan, kind),
        PlanCommand::Preflight { plan } => {
            preflight::run(&GitState::new(&cx.repo_root), &cx.git_tags, *plan)
        }
        PlanCommand::Branch { issue, plan, desc } => {
            branch::run(&GitState::new(&cx.repo_root), issue.as_deref(), *plan, desc)
        }
        PlanCommand::Show { plan } => show::show(&cx.store, *plan),
        PlanCommand::Resume { plan } => show::resume(&cx.store, *plan),
        PlanCommand::Update { plan, status, blocked, message } => show::project_update(
            &cx.store,
            *plan,
            status.as_deref(),
            // clap gives a plain bool for an arity-0 flag; Java's tri-state
            // Boolean is absent-vs-true, so only a present flag is Some.
            if *blocked { Some(true) } else { None },
            message.as_deref(),
        ),
    }
}
