//! Serialises a `DataStoreResolution` to a single JSON line for the skill.
//!
//! The skill parses one shape per status — `ready`, `needs-decision`, or
//! `unresolvable` — and never has to read stderr. Hand-built (matching the
//! Java `ResolutionJson`) so the wire bytes stay identical to the transcripts;
//! the field set is small and fixed.

use std::path::Path;

use crate::ds::resolution::{Choice, NeedsDecision, Unresolvable};

pub fn needs_decision(needs: &NeedsDecision) -> String {
    let options: Vec<String> = needs
        .options
        .iter()
        .map(|o| {
            format!(
                "{{{},{},\"recommended\":{}}}",
                kv("choice", o.choice.token()),
                kv("proposedPath", &o.proposed_path.to_string_lossy()),
                o.recommended
            )
        })
        .collect();
    format!(
        "{{{},{},{},{},\"options\":[{}]}}",
        kv("status", "needs-decision"),
        kv("situation", needs.situation.token()),
        kv("message", needs.situation.message()),
        kv("prompt", &prompt(needs)),
        options.join(",")
    )
}

/// A display-ready, multi-line rendering of the decision the skill shows the
/// user verbatim: the situation message, then one line per option with its
/// path and the recommended one marked. Keeping the rendering here (the brain)
/// keeps the skill's job to "show this and capture the answer".
fn prompt(needs: &NeedsDecision) -> String {
    let mut out = String::from(needs.situation.message());
    for o in &needs.options {
        let kind = if o.recommended { "Recommended" } else { "Alternative" };
        out.push_str(&format!(
            "\n  {} — {}: {}",
            kind,
            o.choice.label(),
            o.proposed_path.to_string_lossy()
        ));
    }
    // When a separate folder is on offer, the proposed path is only a default.
    if needs.options.iter().any(|o| o.choice == Choice::External) {
        out.push_str("\n\nYou can also enter a different folder path.");
    }
    out
}

pub fn unresolvable(bad: &Unresolvable) -> String {
    format!(
        "{{{},{},{}}}",
        kv("status", "unresolvable"),
        kv("reason", bad.reason.name()),
        kv("message", bad.reason.message())
    )
}

/// Settled state: where shipsmooth state lives. `storage_type` is
/// `separate-dir` or `same-repo`; `plans_dir` is the ready-to-read directory
/// holding plan files.
pub fn ready(storage_type: &str, state_root: &Path, plans_dir: &Path) -> String {
    format!(
        "{{{},{},{},{}}}",
        kv("status", "ready"),
        kv("storageType", storage_type),
        kv("stateRoot", &state_root.to_string_lossy()),
        kv("plansDir", &plans_dir.to_string_lossy())
    )
}

fn kv(key: &str, value: &str) -> String {
    format!("\"{key}\":\"{}\"", escape(value))
}

fn escape(s: &str) -> String {
    s.replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('\n', "\\n")
        .replace('\r', "\\r")
        .replace('\t', "\\t")
}

#[cfg(test)]
mod tests {
    //! plan-106 Task 2: the JSON contract is pinned byte-exact against the
    //! Task 1 golden transcripts. Each test rebuilds the resolution the Java
    //! CLI must have held (taking the absolute paths from the transcript
    //! itself, so the tests survive regeneration) and demands the identical
    //! wire line back.

    use super::*;
    use crate::ds::resolution::*;
    use std::path::{Path, PathBuf};

    fn transcript(scenario: &str, capture: &str) -> String {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../fixtures/transcripts/store")
            .join(scenario)
            .join(capture);
        let line = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("missing transcript {}: {e}", path.display()));
        line.trim_end_matches('\n').to_string()
    }

    fn proposed_paths(line: &str) -> Vec<PathBuf> {
        let v: serde_json::Value = serde_json::from_str(line).unwrap();
        v["options"]
            .as_array()
            .unwrap()
            .iter()
            .map(|o| PathBuf::from(o["proposedPath"].as_str().unwrap()))
            .collect()
    }

    #[test]
    fn needs_decision_matches_the_clean_first_run_transcript() {
        let line = transcript("clean-first-run", "info-json.out");
        let paths = proposed_paths(&line);
        let needs = NeedsDecision {
            situation: UndecidableSituation::CleanFirstRun,
            options: vec![
                DecisionOption {
                    choice: Choice::External,
                    proposed_path: paths[0].clone(),
                    recommended: true,
                },
                DecisionOption {
                    choice: Choice::InRepo,
                    proposed_path: paths[1].clone(),
                    recommended: false,
                },
            ],
        };
        assert_eq!(needs_decision(&needs), line);
    }

    #[test]
    fn needs_decision_matches_the_config_dir_missing_transcript() {
        // Single recreate option — and no external offer, so the prompt must
        // NOT carry the "You can also enter a different folder path" trailer.
        let line = transcript("config-dir-missing", "info-json.out");
        let paths = proposed_paths(&line);
        let needs = NeedsDecision {
            situation: UndecidableSituation::ConfigDirMissing,
            options: vec![DecisionOption {
                choice: Choice::RecreateMissingDir,
                proposed_path: paths[0].clone(),
                recommended: true,
            }],
        };
        assert_eq!(needs_decision(&needs), line);
    }

    // Port of the Java `ResolutionJsonTest`: input-controlled cases the
    // transcript pins above cannot cover (every token, escaping edge cases).

    fn clean_first_run_ext_and_in_repo() -> NeedsDecision {
        NeedsDecision {
            situation: UndecidableSituation::CleanFirstRun,
            options: vec![
                DecisionOption {
                    choice: Choice::External,
                    proposed_path: PathBuf::from("/ext"),
                    recommended: true,
                },
                DecisionOption {
                    choice: Choice::InRepo,
                    proposed_path: PathBuf::from("/in"),
                    recommended: false,
                },
            ],
        }
    }

    #[test]
    fn needs_decision_emits_status_situation_and_option_tokens() {
        let json = needs_decision(&clean_first_run_ext_and_in_repo());
        assert!(json.contains("\"status\":\"needs-decision\""), "{json}");
        assert!(json.contains("\"situation\":\"clean-first-run\""), "{json}");
        assert!(json.contains("\"choice\":\"separate-dir\""), "{json}");
        assert!(json.contains("\"choice\":\"same-repo\""), "{json}");
        assert!(json.contains("\"recommended\":true"), "{json}");
        assert!(json.contains("\"recommended\":false"), "{json}");
    }

    #[test]
    fn needs_decision_emits_display_ready_prompt_the_skill_shows_verbatim() {
        let json = needs_decision(&clean_first_run_ext_and_in_repo());

        // A single `prompt` field the skill renders verbatim: the question +
        // each option (human label, path) with the recommended one marked.
        assert!(json.contains("\"prompt\":\""), "prompt field present: {json}");
        assert!(
            json.contains("Where should shipsmooth store all its information"),
            "prompt carries the question: {json}"
        );
        assert!(json.contains("Recommended"), "{json}");
        assert!(json.contains("next to this repo"), "{json}");
        assert!(json.contains("/ext"), "{json}");
        assert!(json.contains("Alternative"), "{json}");
        assert!(json.contains("inside this repo"), "{json}");
        assert!(json.contains("/in"), "{json}");
        // When a separate folder is offered, the prompt invites a custom path.
        assert!(json.contains("enter a different folder path"), "{json}");
        // Multi-line prompt must keep the JSON line valid: real newlines are escaped.
        assert!(json.contains("\\n"), "embedded newlines must be escaped: {json}");
        assert!(!json.contains('\n'), "the JSON must remain a single physical line: {json}");
    }

    #[test]
    fn unresolvable_emits_status_reason_and_message() {
        let bad = Unresolvable::of(UnresolvableReason::LegacyAgentsTree);

        let json = unresolvable(&bad);
        assert!(json.contains("\"status\":\"unresolvable\""), "{json}");
        assert!(json.contains("\"reason\":\"LEGACY_AGENTS_TREE\""), "{json}");
        assert!(json.contains(".shipsmooth"), "{json}");
    }

    #[test]
    fn recreate_and_in_repo_not_set_up_tokens() {
        let recreate = NeedsDecision {
            situation: UndecidableSituation::ConfigDirMissing,
            options: vec![DecisionOption {
                choice: Choice::RecreateMissingDir,
                proposed_path: PathBuf::from("/d"),
                recommended: true,
            }],
        };
        assert!(needs_decision(&recreate).contains("\"choice\":\"recreate\""));

        let in_repo = NeedsDecision {
            situation: UndecidableSituation::InRepoNotSetUp,
            options: vec![DecisionOption {
                choice: Choice::InRepo,
                proposed_path: PathBuf::from("/in"),
                recommended: true,
            }],
        };
        assert!(needs_decision(&in_repo).contains("\"situation\":\"in-repo-not-set-up\""));
    }

    #[test]
    fn message_with_quotes_is_escaped() {
        // A proposed path containing a quote must not break the JSON string.
        let needs = NeedsDecision {
            situation: UndecidableSituation::CleanFirstRun,
            options: vec![DecisionOption {
                choice: Choice::External,
                proposed_path: PathBuf::from("/weird\"path"),
                recommended: true,
            }],
        };
        assert!(needs_decision(&needs).contains("\\\""), "quote must be escaped");
    }

    #[test]
    fn unresolvable_matches_the_transcripts() {
        let malformed = Unresolvable::of(UnresolvableReason::MalformedConfigEntry);
        assert_eq!(unresolvable(&malformed), transcript("malformed-bad-type", "info-json.out"));

        let legacy = Unresolvable::of(UnresolvableReason::LegacyAgentsTree);
        assert_eq!(unresolvable(&legacy), transcript("legacy-agents-tree", "info-json.out"));
    }

    #[test]
    fn ready_matches_the_settled_transcripts() {
        for scenario in ["settled-same-repo", "settled-separate-dir"] {
            let line = transcript(scenario, "info-json.out");
            let v: serde_json::Value = serde_json::from_str(&line).unwrap();
            let rebuilt = ready(
                v["storageType"].as_str().unwrap(),
                Path::new(v["stateRoot"].as_str().unwrap()),
                Path::new(v["plansDir"].as_str().unwrap()),
            );
            assert_eq!(rebuilt, line, "{scenario}");
        }
    }
}
