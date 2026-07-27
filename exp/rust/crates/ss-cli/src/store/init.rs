//! `store init`: acts on the user's first-run choice — creates the chosen
//! state directory and records it in `shipsmooth.toml`, turning a
//! `NeedsDecision` resolution into a `Settled` one.
//!
//! Port of the Java `Init` command. A *guarded* writer: it does not decide for
//! itself whether to act — the dispatch resolves once and passes that
//! resolution in. This refuses to mutate unless the project is genuinely
//! awaiting a decision and the supplied `--type` is one the current situation
//! offers — an already-settled or unresolvable project, or an off-menu choice,
//! is rejected without touching anything. After acting it re-resolves once to
//! confirm the project actually settled.

use std::path::{Path, PathBuf};

use crate::ds::config_writer::ConfigWriter;
use crate::ds::paths::normalize_lexical;
use crate::ds::resolution::{Choice, DataStoreResolution, DecisionOption, NeedsDecision};
use crate::ds::resolver::ProjectDataStoreResolver;
use crate::ds::store::ProjectDataStore;
use crate::store::state_report;

/// Run the guarded init flow. `Ok` is the ready report for stdout (exit 0);
/// `Err` is the failure message for stderr (the caller prefixes `shipsmooth: `
/// and exits 1), mirroring the Java `fail()` shape.
// De-risk shape; hardening groups the project context (repo_root, remote_url).
#[allow(clippy::too_many_arguments)]
pub fn run(
    resolver: &ProjectDataStoreResolver,
    config_writer: &ConfigWriter,
    repo_root: &Path,
    remote_url: Option<&str>,
    resolution: DataStoreResolution,
    type_arg: &str,
    path_arg: Option<&str>,
    json: bool,
) -> Result<String, String> {
    let choice = parse_type(type_arg)
        .ok_or_else(|| format!("unknown --type '{type_arg}' (expected same-repo | separate-dir | recreate)"))?;

    let needs = match resolution {
        DataStoreResolution::Settled(_) => {
            return Err("this project is already configured; nothing to do".to_string());
        }
        DataStoreResolution::Unresolvable(bad) => return Err(bad.message().to_string()),
        DataStoreResolution::NeedsDecision(needs) => needs,
    };

    let option = offered_option(&needs, choice).ok_or_else(|| {
        format!("--type {type_arg} is not valid for the current situation ({:?})", needs.situation)
    })?;

    act(config_writer, repo_root, remote_url, choice, option, path_arg)
        .map_err(|e| e.to_string())?;

    // Confirm the action settled the project.
    match resolver.resolve(repo_root, remote_url) {
        DataStoreResolution::Settled(store) => Ok(state_report::ready(repo_root, &store, json)),
        _ => Err("state did not settle after acting on the choice".to_string()),
    }
}

fn act(
    config_writer: &ConfigWriter,
    repo_root: &Path,
    remote_url: Option<&str>,
    choice: Choice,
    option: &DecisionOption,
    path_arg: Option<&str>,
) -> std::io::Result<()> {
    match choice {
        Choice::External => {
            let dir = resolve_path(path_arg, &option.proposed_path);
            standalone(repo_root, &dir).init()?;
            config_writer.write_external(repo_root, remote_url, &dir)
        }
        Choice::RecreateMissingDir => {
            // Already configured — provision the dir, do not touch the config.
            let dir = resolve_path(path_arg, &option.proposed_path);
            standalone(repo_root, &dir).init()
        }
        Choice::InRepo => {
            // Provision the in-repo data folder so the project resolves settled
            // next run; record the in-repo choice so it is not re-asked.
            std::fs::create_dir_all(repo_root.join(".shipsmooth/plans"))?;
            config_writer.write_in_repo(repo_root, remote_url)
        }
    }
}

fn standalone(repo_root: &Path, state_dir: &Path) -> ProjectDataStore {
    ProjectDataStore::Standalone {
        repo_root: repo_root.to_path_buf(),
        state_dir: state_dir.to_path_buf(),
    }
}

/// The offered option matching the user's choice, if the situation offers it.
fn offered_option(needs: &NeedsDecision, choice: Choice) -> Option<&DecisionOption> {
    needs.options.iter().find(|o| o.choice == choice)
}

/// Use the user-supplied path if given, else the path the resolver proposed.
fn resolve_path(path_arg: Option<&str>, proposed: &Path) -> PathBuf {
    match path_arg {
        Some(p) => normalize_lexical(Path::new(p)),
        None => proposed.to_path_buf(),
    }
}

fn parse_type(arg: &str) -> Option<Choice> {
    match arg.trim() {
        "separate-dir" => Some(Choice::External),
        "same-repo" => Some(Choice::InRepo),
        "recreate" => Some(Choice::RecreateMissingDir),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    //! plan-106 Task 7 de-risk: the same-repo settle round trip and the
    //! already-settled guard. Full `InitTest` port lands in hardening.

    use super::*;

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

    fn run_init(d: &Dirs, type_arg: &str, json: bool) -> Result<String, String> {
        let resolver = ProjectDataStoreResolver::new(d.config.clone());
        let writer = ConfigWriter::new(d.config.clone());
        let resolution = resolver.resolve(&d.repo, None);
        run(&resolver, &writer, &d.repo, None, resolution, type_arg, None, json)
    }

    #[test]
    fn same_repo_choice_settles_and_reports_ready() {
        let d = dirs();

        let out = run_init(&d, "same-repo", true).expect("init must settle");

        assert!(out.contains("\"status\":\"ready\""), "{out}");
        assert!(out.contains("\"storageType\":\"same-repo\""), "{out}");
        assert!(d.repo.join(".shipsmooth/plans").is_dir(), "in-repo data folder provisioned");
        // Settled now: a second init is rejected without touching anything.
        let err = run_init(&d, "same-repo", true).unwrap_err();
        assert_eq!(err, "this project is already configured; nothing to do");
    }

    #[test]
    fn separate_dir_choice_provisions_git_inited_state_dir() {
        let d = dirs();

        let out = run_init(&d, "separate-dir", true).expect("init must settle");

        assert!(out.contains("\"storageType\":\"separate-dir\""), "{out}");
        let state = d.repo.parent().unwrap().join("repo-shipsmooth");
        assert!(state.join(".git").is_dir(), "external state dir must be git-inited");
    }

    #[test]
    fn off_menu_and_unknown_types_are_rejected() {
        let d = dirs();

        // recreate is not offered on a clean first run.
        let err = run_init(&d, "recreate", false).unwrap_err();
        assert!(err.contains("not valid for the current situation"), "{err}");

        let err = run_init(&d, "sideways", false).unwrap_err();
        assert!(err.contains("unknown --type 'sideways'"), "{err}");
    }
}
