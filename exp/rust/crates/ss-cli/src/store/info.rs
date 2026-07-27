//! `store info`: report where this project's shipsmooth state lives, on demand.
//!
//! Port of the Java `Info` command. External-by-default moves plan narratives
//! out of the project repo, so an agent must be told where to read them. This
//! resolves the project and reports the state location — chiefly the
//! `plansDir` the skill points an agent at. With `--json` it emits a single
//! machine-readable line; without it, human-readable text. Runs whether or not
//! state is settled, and always exits 0: a settled project reports `ready`; an
//! unsettled one reports the same needs-decision/unresolvable shape as startup.

use crate::ds::resolution::DataStoreResolution;
use crate::ds::resolver::ProjectDataStoreResolver;
use crate::project::ProjectContext;
use crate::resolution_json;
use crate::store::state_report;

/// The report `store info` prints for this project (the caller prints it).
pub fn report(resolver: &ProjectDataStoreResolver, project: &ProjectContext, json: bool) -> String {
    match resolver.resolve(&project.repo_root, project.remote_url()) {
        DataStoreResolution::Settled(store) => {
            state_report::ready(&project.repo_root, &store, json)
        }
        DataStoreResolution::NeedsDecision(needs) => {
            if json {
                resolution_json::needs_decision(&needs)
            } else {
                "shipsmooth state is not set up yet — run `store init`".to_string()
            }
        }
        DataStoreResolution::Unresolvable(bad) => {
            if json {
                resolution_json::unresolvable(&bad)
            } else {
                format!("shipsmooth state is unresolvable: {}", bad.message())
            }
        }
    }
}

#[cfg(test)]
mod tests {
    //! Full port of the Java `InfoTest`. The Java helpers settle a project via
    //! `ConfigWriter`; until it ports (Task 7) these hand-write the equivalent
    //! config TOML.

    use super::*;
    use std::path::{Path, PathBuf};

    struct Dirs {
        _tmp: tempfile::TempDir,
        config: PathBuf,
        repo: PathBuf,
    }

    fn dirs() -> Dirs {
        let tmp = tempfile::tempdir().unwrap();
        let config = tmp.path().join("shipsmooth.toml");
        let repo = tmp.path().join("repo");
        std::fs::create_dir(&repo).unwrap();
        Dirs { _tmp: tmp, config, repo }
    }

    /// Java `infoForExternal`: write config + provision the state dir.
    fn settle_external(d: &Dirs, state_dir: &Path) {
        std::fs::create_dir_all(state_dir.join("plans")).unwrap();
        std::fs::write(
            &d.config,
            format!(
                "[[projects]]\nlocalPath = '{}'\nstorageRoot = '{}'\nstorageType = 'separate-dir'\n",
                d.repo.display(),
                state_dir.display()
            ),
        )
        .unwrap();
    }

    /// Java `infoForInRepo`: write config + provision .shipsmooth/plans.
    fn settle_in_repo(d: &Dirs) {
        std::fs::create_dir_all(d.repo.join(".shipsmooth/plans")).unwrap();
        std::fs::write(
            &d.config,
            format!(
                "[[projects]]\nlocalPath = '{}'\nstorageType = 'same-repo'\n",
                d.repo.display()
            ),
        )
        .unwrap();
    }

    fn run(d: &Dirs, json: bool) -> String {
        report(
            &ProjectDataStoreResolver::new(d.config.clone()),
            &crate::project::ProjectContext::without_remote(&d.repo),
            json,
        )
    }

    #[test]
    fn json_settled_external_reports_ready_mode_and_plans_dir() {
        let d = dirs();
        let state = d.repo.parent().unwrap().join("state");
        settle_external(&d, &state);

        let json = run(&d, true);

        assert!(json.contains("\"status\":\"ready\""), "{json}");
        assert!(json.contains("\"storageType\":\"separate-dir\""), "{json}");
        assert!(json.contains(&format!("\"stateRoot\":\"{}\"", state.display())), "{json}");
        // plansDir hangs directly off the external state root (no .shipsmooth segment).
        assert!(
            json.contains(&format!("\"plansDir\":\"{}\"", state.join("plans").display())),
            "{json}"
        );
    }

    #[test]
    fn json_settled_in_repo_plans_dir_includes_shipsmooth_segment() {
        let d = dirs();
        settle_in_repo(&d);

        let json = run(&d, true);

        assert!(json.contains("\"storageType\":\"same-repo\""), "{json}");
        // Same-repo layout inserts the .shipsmooth segment between repo root and plans/.
        let expected_plans = d.repo.join(".shipsmooth/plans");
        assert!(
            json.contains(&format!("\"plansDir\":\"{}\"", expected_plans.display())),
            "{json}"
        );
    }

    #[test]
    fn no_flag_settled_external_prints_human_text_with_plans_path() {
        let d = dirs();
        let state = d.repo.parent().unwrap().join("state");
        settle_external(&d, &state);

        let text = run(&d, false);

        assert!(text.contains(&format!("separate-dir storage at {}", state.display())), "{text}");
        assert!(text.contains(&format!("plans: {}", state.join("plans").display())), "{text}");
        assert!(!text.contains('{'), "default output is human text, not JSON: {text}");
    }

    #[test]
    fn json_unsettled_emits_resolution_shape_not_ready() {
        let d = dirs(); // no config written: clean first run

        let json = run(&d, true);

        assert!(!json.contains("\"status\":\"ready\""), "{json}");
        assert!(json.contains("\"status\":\"needs-decision\""), "{json}");
    }

    #[test]
    fn no_flag_unsettled_prints_human_not_set_up_message() {
        let d = dirs();

        let text = run(&d, false);

        assert!(text.contains("not set up yet"), "{text}");
        assert!(!text.contains('{'), "default output is human text, not JSON: {text}");
    }

    #[test]
    fn unresolvable_legacy_tree_is_reported() {
        let d = dirs();
        // A legacy .agents/plans/ tree makes the project unresolvable.
        std::fs::create_dir_all(d.repo.join(".agents/plans")).unwrap();

        let json = run(&d, true);
        assert!(json.contains("\"status\":\"unresolvable\""), "{json}");

        let text = run(&d, false);
        assert!(text.contains("unresolvable"), "{text}");
    }
}
