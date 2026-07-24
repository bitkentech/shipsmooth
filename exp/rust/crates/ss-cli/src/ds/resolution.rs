//! Outcome of resolving where a project's shipsmooth state lives.
//!
//! Port of the Java `DataStoreResolution` sealed interface: exactly three
//! shapes. `Settled` — proceed with the given store. `NeedsDecision` — the CLI
//! cannot decide alone; it hands the skill a set of options (one recommended)
//! and never prompts on stdin. `Unresolvable` — the user must fix it by hand;
//! `resolve()` returns this rather than erroring for these cases.

use std::path::PathBuf;

use crate::ds::store::ProjectDataStore;

pub enum DataStoreResolution {
    Settled(ProjectDataStore),
    NeedsDecision(NeedsDecision),
    Unresolvable(Unresolvable),
}

/// Unsettled: the user must choose. Carries why a decision is needed and the
/// concrete options to offer; exactly one option is marked recommended.
pub struct NeedsDecision {
    pub situation: UndecidableSituation,
    pub options: Vec<DecisionOption>,
}

/// The CLI cannot proceed; the user must fix the situation by hand.
pub struct Unresolvable {
    pub reason: UnresolvableReason,
}

/// One choice the user can take, with the path the CLI proposes for it.
pub struct DecisionOption {
    pub choice: Choice,
    pub proposed_path: PathBuf,
    pub recommended: bool,
}

/// Why a decision is needed — lets the skill layer word the prompt.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum UndecidableSituation {
    /// Nothing configured and no state anywhere: offer external (recommended) or in-repo.
    CleanFirstRun,
    /// A config entry names an external state dir that no longer exists: offer to recreate.
    ConfigDirMissing,
    /// A config entry selects in-repo mode but the in-repo data folder is not set up yet.
    InRepoNotSetUp,
}

impl UndecidableSituation {
    /// A generic, human-readable description of this situation.
    pub fn message(&self) -> &'static str {
        match self {
            UndecidableSituation::CleanFirstRun => {
                "Where should shipsmooth store all its information for this project?"
            }
            UndecidableSituation::ConfigDirMissing => {
                "The configured external state directory no longer exists; choose whether to recreate it."
            }
            UndecidableSituation::InRepoNotSetUp => {
                "This project is configured for in-repo state, but the .shipsmooth/ folder is not set up yet."
            }
        }
    }

    /// Stable wire token for the skill (kebab-case, independent of enum naming).
    pub fn token(&self) -> &'static str {
        match self {
            UndecidableSituation::CleanFirstRun => "clean-first-run",
            UndecidableSituation::ConfigDirMissing => "config-dir-missing",
            UndecidableSituation::InRepoNotSetUp => "in-repo-not-set-up",
        }
    }
}

/// The kinds of choice offered in a `NeedsDecision`.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Choice {
    External,
    InRepo,
    RecreateMissingDir,
}

impl Choice {
    /// Stable wire token — must match `store init --type`'s accepted values
    /// exactly: `separate-dir` / `same-repo` / `recreate`.
    pub fn token(&self) -> &'static str {
        match self {
            Choice::External => "separate-dir",
            Choice::InRepo => "same-repo",
            Choice::RecreateMissingDir => "recreate",
        }
    }

    /// Human-facing label for this option in the prompt (shown verbatim).
    pub fn label(&self) -> &'static str {
        match self {
            Choice::External => "a separate folder next to this repo",
            Choice::InRepo => "inside this repo",
            Choice::RecreateMissingDir => "recreate the configured folder",
        }
    }
}

/// Why state is unresolvable. `Unknown` is the catch-all for anticipated but
/// unenumerated failures; keeping the enum closed keeps matches exhaustive.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum UnresolvableReason {
    LegacyAgentsTree,
    MalformedConfigEntry,
    AmbiguousState,
    Unknown,
}

impl UnresolvableReason {
    /// The wire token: the raw Java enum name (SCREAMING_SNAKE_CASE).
    pub fn name(&self) -> &'static str {
        match self {
            UnresolvableReason::LegacyAgentsTree => "LEGACY_AGENTS_TREE",
            UnresolvableReason::MalformedConfigEntry => "MALFORMED_CONFIG_ENTRY",
            UnresolvableReason::AmbiguousState => "AMBIGUOUS_STATE",
            UnresolvableReason::Unknown => "UNKNOWN",
        }
    }

    /// A generic, human-readable description of this reason.
    pub fn message(&self) -> &'static str {
        match self {
            UnresolvableReason::LegacyAgentsTree => {
                "A legacy .agents/shipsmooth data tree was found; rename it to .shipsmooth/ by hand."
            }
            UnresolvableReason::MalformedConfigEntry => {
                "A matching config entry is malformed (no state directory and no valid mode)."
            }
            UnresolvableReason::AmbiguousState => {
                "The on-disk and configured state are contradictory or corrupt and cannot be reconciled automatically."
            }
            UnresolvableReason::Unknown => {
                "An unexpected error occurred while determining where state lives."
            }
        }
    }
}
