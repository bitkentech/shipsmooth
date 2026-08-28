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
use crate::project::ProjectContext;
use crate::store::state_report;

/// Run the guarded init flow. `Ok` is the ready report for stdout (exit 0);
/// `Err` is the failure message for stderr (the caller prefixes `shipsmooth: `
/// and exits 1), mirroring the Java `fail()` shape.
pub fn run(
    resolver: &ProjectDataStoreResolver,
    config_writer: &ConfigWriter,
    project: &ProjectContext,
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
        format!(
            "--type {type_arg} is not valid for the current situation ({})",
            needs.situation.name()
        )
    })?;

    act(config_writer, project, choice, option, path_arg).map_err(|e| e.to_string())?;

    // Confirm the action settled the project.
    match resolver.resolve(&project.repo_root, project.remote_url()) {
        DataStoreResolution::Settled(store) => {
            Ok(state_report::ready(&project.repo_root, &store, json))
        }
        _ => Err("state did not settle after acting on the choice".to_string()),
    }
}

fn act(
    config_writer: &ConfigWriter,
    project: &ProjectContext,
    choice: Choice,
    option: &DecisionOption,
    path_arg: Option<&str>,
) -> std::io::Result<()> {
    match choice {
        Choice::External => {
            let dir = resolve_path(path_arg, &option.proposed_path);
            standalone(&project.repo_root, &dir).init()?;
            config_writer.write_external(&project.repo_root, project.remote_url(), &dir)
        }
        Choice::RecreateMissingDir => {
            // Already configured — provision the dir, do not touch the config.
            let dir = resolve_path(path_arg, &option.proposed_path);
            standalone(&project.repo_root, &dir).init()
        }
        Choice::InRepo => {
            // Provision the in-repo data folder so the project resolves settled
            // next run; record the in-repo choice so it is not re-asked.
            std::fs::create_dir_all(project.repo_root.join(".shipsmooth/plans"))?;
            config_writer.write_in_repo(&project.repo_root, project.remote_url())
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
    //! Full port of the Java `InitTest`: each test hand-builds a resolution
    //! (as the dispatch would) and runs against an isolated config file.

    use super::*;
    use crate::ds::resolution::UndecidableSituation;
    use crate::ds::resolution::{Unresolvable, UnresolvableReason};

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

    fn option(choice: Choice, path: &Path, recommended: bool) -> DecisionOption {
        DecisionOption { choice, proposed_path: path.to_path_buf(), recommended }
    }

    fn needs(situation: UndecidableSituation, options: Vec<DecisionOption>) -> DataStoreResolution {
        DataStoreResolution::NeedsDecision(NeedsDecision { situation, options })
    }

    /// The Java `boundInit` + `run` pair: run the flow against `d`'s config
    /// with a hand-built resolution.
    fn run_bound(
        d: &Dirs,
        resolution: DataStoreResolution,
        type_arg: &str,
        path_arg: Option<&str>,
        json: bool,
    ) -> Result<String, String> {
        run(
            &ProjectDataStoreResolver::new(d.config.clone()),
            &ConfigWriter::new(d.config.clone()),
            &ProjectContext::without_remote(&d.repo),
            resolution,
            type_arg,
            path_arg,
            json,
        )
    }

    /// The dispatch shape: resolve first, then run on that resolution.
    fn run_resolved(d: &Dirs, type_arg: &str, json: bool) -> Result<String, String> {
        let resolution =
            ProjectDataStoreResolver::new(d.config.clone()).resolve(&d.repo, None);
        run_bound(d, resolution, type_arg, None, json)
    }

    #[test]
    fn external_json_emits_ready_shape_on_success() {
        let d = dirs();
        let external = d.repo.parent().unwrap().join("ext");
        let resolution = needs(
            UndecidableSituation::CleanFirstRun,
            vec![option(Choice::External, &external, true)],
        );

        let out = run_bound(&d, resolution, "separate-dir", Some(external.to_str().unwrap()), true)
            .expect("init must settle");

        assert!(out.contains("\"status\":\"ready\""), "{out}");
        assert!(out.contains("\"storageType\":\"separate-dir\""), "{out}");
        assert!(
            out.contains(&format!("\"plansDir\":\"{}\"", external.join("plans").display())),
            "{out}"
        );
    }

    #[test]
    fn external_creates_dir_writes_config_and_settles() {
        let d = dirs();
        let external = d.repo.parent().unwrap().join("ext");
        let resolution = needs(
            UndecidableSituation::CleanFirstRun,
            vec![
                option(Choice::External, &external, true),
                option(Choice::InRepo, &d.repo.join(".shipsmooth"), false),
            ],
        );

        run_bound(&d, resolution, "separate-dir", Some(external.to_str().unwrap()), false)
            .expect("init must settle");

        assert!(external.join(".git").is_dir(), "external state repo created");
        // config now resolves settled-standalone for this repo
        match ProjectDataStoreResolver::new(d.config.clone()).resolve(&d.repo, None) {
            DataStoreResolution::Settled(ProjectDataStore::Standalone { .. }) => {}
            _ => panic!("expected Settled(Standalone)"),
        }
    }

    #[test]
    fn in_repo_creates_folder_writes_config_and_settles() {
        let d = dirs();
        let resolution = needs(
            UndecidableSituation::CleanFirstRun,
            vec![
                option(Choice::External, &d.repo.parent().unwrap().join("ext"), true),
                option(Choice::InRepo, &d.repo.join(".shipsmooth"), false),
            ],
        );

        run_bound(&d, resolution, "same-repo", None, false).expect("init must settle");

        assert!(d.repo.join(".shipsmooth/plans").is_dir(), "in-repo folder created");
        match ProjectDataStoreResolver::new(d.config.clone()).resolve(&d.repo, None) {
            DataStoreResolution::Settled(ProjectDataStore::InRepo { .. }) => {}
            _ => panic!("expected Settled(InRepo)"),
        }
    }

    #[test]
    fn recreate_provisions_configured_dir_without_changing_config() {
        let d = dirs();
        let state_dir = d.repo.parent().unwrap().join("gone");
        // Pre-existing external config pointing at the missing dir.
        ConfigWriter::new(d.config.clone()).write_external(&d.repo, None, &state_dir).unwrap();
        let config_before = std::fs::read_to_string(&d.config).unwrap();
        let resolution = needs(
            UndecidableSituation::ConfigDirMissing,
            vec![option(Choice::RecreateMissingDir, &state_dir, true)],
        );

        run_bound(&d, resolution, "recreate", Some(state_dir.to_str().unwrap()), false)
            .expect("init must settle");

        assert!(state_dir.join(".git").is_dir(), "configured dir recreated");
        assert_eq!(
            std::fs::read_to_string(&d.config).unwrap(),
            config_before,
            "recreate must not touch the config"
        );
    }

    #[test]
    fn already_settled_refuses_without_mutating() {
        let d = dirs();
        let settled = DataStoreResolution::Settled(ProjectDataStore::InRepo {
            repo_root: d.repo.clone(),
        });

        let err = run_bound(&d, settled, "same-repo", None, false).unwrap_err();

        assert_eq!(err, "this project is already configured; nothing to do");
        assert!(!d.config.exists(), "no config should be written");
    }

    #[test]
    fn unresolvable_refuses() {
        let d = dirs();
        let bad = DataStoreResolution::Unresolvable(Unresolvable::of(
            UnresolvableReason::LegacyAgentsTree,
        ));

        let err = run_bound(&d, bad, "separate-dir", None, false).unwrap_err();

        assert_eq!(err, UnresolvableReason::LegacyAgentsTree.message());
    }

    #[test]
    fn off_menu_choice_refuses() {
        let d = dirs();
        // CONFIG_DIR_MISSING only offers RECREATE; choosing external is off-menu.
        let resolution = needs(
            UndecidableSituation::ConfigDirMissing,
            vec![option(Choice::RecreateMissingDir, &d.repo.parent().unwrap().join("d"), true)],
        );

        let err = run_bound(&d, resolution, "separate-dir", None, false).unwrap_err();

        assert!(err.contains("not valid for the current situation"), "{err}");
    }

    #[test]
    fn unknown_type_token_refuses() {
        let d = dirs();
        let resolution = needs(
            UndecidableSituation::CleanFirstRun,
            vec![option(Choice::InRepo, &d.repo.join(".shipsmooth"), true)],
        );

        let err = run_bound(&d, resolution, "sideways", None, false).unwrap_err();

        assert!(err.contains("unknown --type 'sideways'"), "{err}");
    }

    // ── the dispatch shape end-to-end (beyond the Java file: resolve → run) ──

    #[test]
    fn same_repo_choice_settles_and_second_init_is_rejected() {
        let d = dirs();

        let out = run_resolved(&d, "same-repo", true).expect("init must settle");

        assert!(out.contains("\"status\":\"ready\""), "{out}");
        // Settled now: a second init is rejected without touching anything.
        let err = run_resolved(&d, "same-repo", true).unwrap_err();
        assert_eq!(err, "this project is already configured; nothing to do");
    }

    #[test]
    fn separate_dir_choice_provisions_git_inited_proposed_sibling() {
        let d = dirs();

        let out = run_resolved(&d, "separate-dir", true).expect("init must settle");

        assert!(out.contains("\"storageType\":\"separate-dir\""), "{out}");
        let state = d.repo.parent().unwrap().join("repo-shipsmooth");
        assert!(state.join(".git").is_dir(), "external state dir must be git-inited");
    }
}
