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

impl NeedsDecision {
    /// The single option to present as the default/recommended choice.
    ///
    /// Panics if no option is marked: a `NeedsDecision` without a recommended
    /// option is a programming error, not a user-facing condition.
    pub fn recommended(&self) -> &DecisionOption {
        self.options
            .iter()
            .find(|o| o.recommended)
            .expect("NeedsDecision must mark exactly one option recommended")
    }
}

/// The CLI cannot proceed; the user must fix the situation by hand. The
/// human-facing text comes from the reason; `cause` carries the underlying
/// error for diagnostics when present (e.g. an unexpected I/O failure) — the
/// anticipated reasons carry no cause.
pub struct Unresolvable {
    pub reason: UnresolvableReason,
    pub cause: Option<Box<dyn std::error::Error + Send + Sync>>,
}

impl Unresolvable {
    /// An anticipated failure described entirely by its reason; no underlying error.
    pub fn of(reason: UnresolvableReason) -> Self {
        Unresolvable { reason, cause: None }
    }

    /// An unexpected failure, with its cause retained for diagnostics.
    pub fn unknown(cause: impl Into<Box<dyn std::error::Error + Send + Sync>>) -> Self {
        Unresolvable { reason: UnresolvableReason::Unknown, cause: Some(cause.into()) }
    }

    /// The human-readable description of why state is unresolvable.
    pub fn message(&self) -> &'static str {
        self.reason.message()
    }
}

/// One choice the user can take, with the path the CLI proposes for it.
pub struct DecisionOption {
    pub choice: Choice,
    pub proposed_path: PathBuf,
    pub recommended: bool,
}

/// Why a decision is needed — lets the skill layer word the prompt.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum UndecidableSituation {
    /// Nothing configured and no state anywhere: offer external (recommended) or in-repo.
    CleanFirstRun,
    /// A config entry names an external state dir that no longer exists: offer to recreate.
    ConfigDirMissing,
    /// A config entry selects in-repo mode but the in-repo data folder is not set up yet.
    InRepoNotSetUp,
}

impl UndecidableSituation {
    /// Every situation, for exhaustive checks (Java gets `values()` for free).
    pub const ALL: [Self; 3] = [
        UndecidableSituation::CleanFirstRun,
        UndecidableSituation::ConfigDirMissing,
        UndecidableSituation::InRepoNotSetUp,
    ];

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
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
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
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum UnresolvableReason {
    LegacyAgentsTree,
    MalformedConfigEntry,
    AmbiguousState,
    Unknown,
}

impl UnresolvableReason {
    /// Every reason, for exhaustive checks (Java gets `values()` for free).
    pub const ALL: [Self; 4] = [
        UnresolvableReason::LegacyAgentsTree,
        UnresolvableReason::MalformedConfigEntry,
        UnresolvableReason::AmbiguousState,
        UnresolvableReason::Unknown,
    ];

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

#[cfg(test)]
mod tests {
    //! Port of the Java `DataStoreResolutionTest`: behaviour of the model itself.

    use super::*;

    fn clean_first_run(options: Vec<DecisionOption>) -> NeedsDecision {
        NeedsDecision { situation: UndecidableSituation::CleanFirstRun, options }
    }

    fn option(choice: Choice, path: &str, recommended: bool) -> DecisionOption {
        DecisionOption { choice, proposed_path: PathBuf::from(path), recommended }
    }

    #[test]
    fn recommended_returns_the_marked_option() {
        let needs = clean_first_run(vec![
            option(Choice::External, "/ext", true),
            option(Choice::InRepo, "/in", false),
        ]);

        assert_eq!(needs.recommended().choice, Choice::External);
    }

    #[test]
    #[should_panic(expected = "NeedsDecision must mark exactly one option recommended")]
    fn recommended_panics_when_no_option_marked() {
        let needs = clean_first_run(vec![option(Choice::InRepo, "/in", false)]);
        needs.recommended();
    }

    #[test]
    fn unknown_factory_sets_unknown_reason_and_retains_cause() {
        let cause = std::io::Error::other("boom");
        let bad = Unresolvable::unknown(cause);

        assert_eq!(bad.reason, UnresolvableReason::Unknown);
        assert_eq!(bad.cause.as_ref().map(|c| c.to_string()), Some("boom".to_string()));
        assert_eq!(bad.message(), UnresolvableReason::Unknown.message());
    }

    #[test]
    fn of_factory_carries_no_cause() {
        let bad = Unresolvable::of(UnresolvableReason::LegacyAgentsTree);
        assert!(bad.cause.is_none());
        assert_eq!(bad.message(), UnresolvableReason::LegacyAgentsTree.message());
    }

    #[test]
    fn every_situation_and_reason_has_a_non_blank_message() {
        for s in UndecidableSituation::ALL {
            assert!(!s.message().trim().is_empty(), "{}", s.token());
        }
        for r in UnresolvableReason::ALL {
            assert!(!r.message().trim().is_empty(), "{}", r.name());
        }
    }
}
