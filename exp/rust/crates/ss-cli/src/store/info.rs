//! `store info`: report where this project's shipsmooth state lives, on demand.
//!
//! Port of the Java `Info` command. External-by-default moves plan narratives
//! out of the project repo, so an agent must be told where to read them. This
//! resolves the project and reports the state location — chiefly the
//! `plansDir` the skill points an agent at. With `--json` it emits a single
//! machine-readable line; without it, human-readable text. Runs whether or not
//! state is settled, and always exits 0: a settled project reports `ready`; an
//! unsettled one reports the same needs-decision/unresolvable shape as startup.

use std::path::Path;

use crate::ds::resolution::DataStoreResolution;
use crate::ds::resolver::ProjectDataStoreResolver;
use crate::resolution_json;
use crate::store::state_report;

/// The report `store info` prints for this project (the caller prints it).
pub fn report(
    resolver: &ProjectDataStoreResolver,
    repo_root: &Path,
    remote_url: Option<&str>,
    json: bool,
) -> String {
    match resolver.resolve(repo_root, remote_url) {
        DataStoreResolution::Settled(store) => state_report::ready(repo_root, &store, json),
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
    //! plan-106 Task 6 de-risk: the settled-external ready shape (plansDir via
    //! the ss-core locator) and the unsettled human text. Full `InfoTest` port
    //! lands in hardening.

    use super::*;

    fn resolver_for(config: &Path) -> ProjectDataStoreResolver {
        ProjectDataStoreResolver::new(config.to_path_buf())
    }

    #[test]
    fn json_settled_external_reports_ready_mode_and_plans_dir() {
        let tmp = tempfile::tempdir().unwrap();
        let repo = tmp.path().join("repo");
        let state = tmp.path().join("state");
        std::fs::create_dir(&repo).unwrap();
        std::fs::create_dir_all(state.join("plans")).unwrap();
        let config = tmp.path().join("shipsmooth.toml");
        std::fs::write(
            &config,
            format!(
                "[[projects]]\nlocalPath = '{}'\nstorageRoot = '{}'\nstorageType = 'separate-dir'\n",
                repo.display(),
                state.display()
            ),
        )
        .unwrap();

        let json = report(&resolver_for(&config), &repo, None, true);

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
    fn no_flag_unsettled_prints_human_not_set_up_message() {
        let tmp = tempfile::tempdir().unwrap();
        let repo = tmp.path().join("repo");
        std::fs::create_dir(&repo).unwrap();
        let config = tmp.path().join("shipsmooth.toml"); // never written: clean first run

        let text = report(&resolver_for(&config), &repo, None, false);

        assert!(text.contains("not set up yet"), "{text}");
        assert!(!text.contains('{'), "default output is human text, not JSON: {text}");
    }
}
