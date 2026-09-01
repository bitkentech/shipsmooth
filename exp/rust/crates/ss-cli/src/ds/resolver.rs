//! Resolves where a project's shipsmooth state lives — the plan-85 branch table.
//!
//! Port of the Java `ProjectDataStoreResolver`. Detection only: `resolve()`
//! reads the config file and the filesystem, then classifies. It never
//! creates, moves, or git-inits anything, and never prompts on stdin — acting
//! on a `NeedsDecision` is a separate, deferred concern. Matching is by the
//! pair `(localPath, remoteUrl)`.

use std::path::{Path, PathBuf};

use crate::ds::config::{ProjectEntry, StandaloneConfig};
use crate::ds::legacy_guard;
use crate::ds::manifest::Manifest;
use crate::ds::paths::normalize_lexical;
use crate::ds::resolution::{
    Choice, DataStoreResolution, DecisionOption, NeedsDecision, UndecidableSituation,
    Unresolvable, UnresolvableReason,
};
use crate::ds::store::ProjectDataStore;

/// Tool-owned in-repo data folder; its `plans/` subtree marks settled in-repo state.
const DATA_DIR: &str = ".shipsmooth";
const PLANS_SUBDIR: &str = "plans";

const STORAGE_EMBEDDED: &str = "same-repo";
const STORAGE_FILESYSTEM: &str = "separate-dir";

pub struct ProjectDataStoreResolver {
    /// Injected config-file location (Java's `ConfigFileLocator`), so tests
    /// never touch the real `~/.config/shipsmooth/shipsmooth.toml`.
    config_file: PathBuf,
}

impl ProjectDataStoreResolver {
    pub fn new(config_file: PathBuf) -> ProjectDataStoreResolver {
        ProjectDataStoreResolver { config_file }
    }

    /// Classify per the plan-85 branch table: `Settled` when the location is
    /// known, `NeedsDecision` when the user must choose, `Unresolvable` when
    /// the user must fix it by hand. Never errors for these cases.
    ///
    /// `local_path` is the canonical repo root (`git rev-parse --show-toplevel`);
    /// `remote_url` the origin URL if present.
    pub fn resolve(&self, local_path: &Path, remote_url: Option<&str>) -> DataStoreResolution {
        if let Some(config) = StandaloneConfig::parse(&self.config_file) {
            if let Some(entry) = config.matching_entry(local_path, remote_url) {
                return from_config_entry(local_path, entry);
            }
        }
        from_filesystem(local_path)
    }
}

/// A config entry matched this project. Classify per its `storageType` /
/// `storageRoot`: a valid same-repo entry resolves in-repo; a valid
/// separate-dir entry resolves to its root (recreate decision if missing);
/// anything inconsistent is a malformed entry.
fn from_config_entry(local_path: &Path, entry: &ProjectEntry) -> DataStoreResolution {
    let has_storage_root = entry.storage_root.as_deref().is_some_and(|r| !r.trim().is_empty());
    match entry.storage_type.as_deref().map(str::trim) {
        // Embedded entries must NOT also carry a storageRoot.
        Some(STORAGE_EMBEDDED) if !has_storage_root => from_in_repo_entry(local_path),
        // separate-dir storage: a storageRoot is required.
        Some(STORAGE_FILESYSTEM) if has_storage_root => from_external_entry(local_path, entry),
        // Missing or unknown storageType value, or an invalid combination.
        _ => malformed(),
    }
}

/// Valid separate-dir entry: settled when the root exists, else offer to recreate it.
fn from_external_entry(local_path: &Path, entry: &ProjectEntry) -> DataStoreResolution {
    let storage_root = normalize_lexical(Path::new(entry.storage_root.as_deref().unwrap()));
    if storage_root.is_dir() {
        // Config wins over any in-repo folder: we do not even inspect the repo here.
        return DataStoreResolution::Settled(ProjectDataStore::Standalone {
            repo_root: local_path.to_path_buf(),
            state_dir: storage_root,
        });
    }
    DataStoreResolution::NeedsDecision(NeedsDecision {
        situation: UndecidableSituation::ConfigDirMissing,
        options: vec![DecisionOption {
            choice: Choice::RecreateMissingDir,
            proposed_path: storage_root,
            recommended: true,
        }],
    })
}

/// Valid in-repo entry: settled only once the in-repo folder is actually set
/// up. The entry records the choice, but settled-ness still requires the
/// on-disk folder, so an unprovisioned repo is offered the in-repo setup
/// rather than re-asked from scratch.
fn from_in_repo_entry(local_path: &Path) -> DataStoreResolution {
    settled_in_repo(local_path).unwrap_or_else(|| {
        DataStoreResolution::NeedsDecision(NeedsDecision {
            situation: UndecidableSituation::InRepoNotSetUp,
            options: vec![DecisionOption {
                choice: Choice::InRepo,
                proposed_path: local_path.join(DATA_DIR),
                recommended: true,
            }],
        })
    })
}

/// Settled in-repo iff the tool-owned `.shipsmooth/` is recognisably ours —
/// the single definition of what "in-repo state is set up" means. Two ways to
/// be recognisably ours:
///
/// - it carries the `manifest.toml` marker (PB-360) — an authoritative,
///   recorded fact that `store init` created the folder; or
/// - for folders created before the marker existed, the `.shipsmooth/plans/`
///   subtree is present.
///
/// A `.shipsmooth/` with neither is not (yet) our state. The marker is purely
/// additive: dropping it changes nothing for any existing corpus, which is
/// covered by `plans/` alone.
fn settled_in_repo(local_path: &Path) -> Option<DataStoreResolution> {
    let data_dir = local_path.join(DATA_DIR);
    let owned = has_state_store_manifest(&data_dir) || data_dir.join(PLANS_SUBDIR).is_dir();
    if !owned {
        return None;
    }
    Some(DataStoreResolution::Settled(ProjectDataStore::InRepo {
        repo_root: local_path.to_path_buf(),
    }))
}

/// True when `<data_dir>/manifest.toml` parses as a shipsmooth state-store
/// marker. A missing / unparseable / foreign file is not a marker — resolution
/// then falls through to today's logic (no hard failure on a stray file).
fn has_state_store_manifest(data_dir: &Path) -> bool {
    Manifest::read(&data_dir.join(ss_core::conf::MANIFEST_FILE))
        .is_some_and(|m| m.is_state_store())
}

fn malformed() -> DataStoreResolution {
    DataStoreResolution::Unresolvable(Unresolvable::of(UnresolvableReason::MalformedConfigEntry))
}

/// No matching config entry: legacy guard, settled in-repo, or a clean first run.
fn from_filesystem(local_path: &Path) -> DataStoreResolution {
    if legacy_guard::is_legacy_data_tree(local_path) {
        return DataStoreResolution::Unresolvable(Unresolvable::of(
            UnresolvableReason::LegacyAgentsTree,
        ));
    }
    settled_in_repo(local_path).unwrap_or_else(|| clean_first_run(local_path))
}

/// Nothing configured and no state anywhere: offer external (recommended) or in-repo.
fn clean_first_run(local_path: &Path) -> DataStoreResolution {
    DataStoreResolution::NeedsDecision(NeedsDecision {
        situation: UndecidableSituation::CleanFirstRun,
        options: vec![
            DecisionOption {
                choice: Choice::External,
                proposed_path: proposed_external_path(local_path),
                recommended: true,
            },
            DecisionOption {
                choice: Choice::InRepo,
                proposed_path: local_path.join(DATA_DIR),
                recommended: false,
            },
        ],
    })
}

/// Propose the external state location as a *sibling* of the project repo —
/// `<parent>/<repo>-shipsmooth`, recorded verbatim on accept, never
/// hash-derived. Deliberately next to the repo, not hidden under
/// `~/.local/state`: the external dir is the user's project content (plan
/// narratives, task history, its own git repo) which they may push to a
/// remote — not ephemeral local-install state — so it must be discoverable.
fn proposed_external_path(local_path: &Path) -> PathBuf {
    let repo = normalize_lexical(local_path);
    let repo_name = match repo.file_name() {
        Some(name) => name.to_string_lossy().into_owned(),
        None => "project".to_string(),
    };
    let sibling = format!("{repo_name}-shipsmooth");
    match repo.parent() {
        Some(parent) => parent.join(sibling),
        None => PathBuf::from(sibling),
    }
}

#[cfg(test)]
mod tests {
    //! Full port of the Java `ProjectDataStoreResolverTest` — one test per row
    //! of the plan-85 branch table, asserting the returned
    //! `DataStoreResolution` variant. Sections mirror the Java file.

    use super::*;
    use tempfile::TempDir;

    fn repo() -> TempDir {
        tempfile::tempdir().unwrap()
    }

    /// Java's `writeConfig`: the config file lives inside the repo tempdir.
    fn write_config(repo: &Path, toml: &str) -> PathBuf {
        let file = repo.join("shipsmooth.toml");
        std::fs::write(&file, toml).unwrap();
        file
    }

    /// Java's `resolve` helper: resolver over an injected config file, with
    /// the tempdir as the project's local path.
    fn resolve(config: PathBuf, repo: &Path, remote_url: Option<&str>) -> DataStoreResolution {
        ProjectDataStoreResolver::new(config).resolve(repo, remote_url)
    }

    fn settled(r: DataStoreResolution) -> ProjectDataStore {
        match r {
            DataStoreResolution::Settled(store) => store,
            _ => panic!("expected Settled"),
        }
    }

    fn needs_decision(r: DataStoreResolution) -> NeedsDecision {
        match r {
            DataStoreResolution::NeedsDecision(n) => n,
            _ => panic!("expected NeedsDecision"),
        }
    }

    fn unresolvable(r: DataStoreResolution) -> Unresolvable {
        match r {
            DataStoreResolution::Unresolvable(u) => u,
            _ => panic!("expected Unresolvable"),
        }
    }

    fn standalone_state_dir(store: ProjectDataStore) -> PathBuf {
        match store {
            ProjectDataStore::Standalone { state_dir, .. } => state_dir,
            _ => panic!("expected Standalone store"),
        }
    }

    // ── Settled: matched external config entry whose dir exists ──────────────

    #[test]
    fn config_filesystem_dir_exists_settled_standalone() {
        let repo = repo();
        let storage_root = repo.path().join("state");
        std::fs::create_dir_all(&storage_root).unwrap();
        let config = write_config(
            repo.path(),
            &format!(
                "[[projects]]\n\
                 remoteUrl = 'https://github.com/org/repo.git'\n\
                 localPath = '{}'\n\
                 storageRoot = '{}'\n\
                 storageType = 'separate-dir'\n",
                repo.path().display(),
                storage_root.display()
            ),
        );

        let store = settled(resolve(config, repo.path(), Some("https://github.com/org/repo.git")));
        assert_eq!(standalone_state_dir(store), normalize_lexical(&storage_root));
    }

    #[test]
    fn no_remote_matches_on_local_path_alone() {
        let repo = repo();
        let storage_root = repo.path().join("state");
        std::fs::create_dir_all(&storage_root).unwrap();
        let config = write_config(
            repo.path(),
            &format!(
                "[[projects]]\n\
                 localPath = '{}'\n\
                 storageRoot = '{}'\n\
                 storageType = 'separate-dir'\n",
                repo.path().display(),
                storage_root.display()
            ),
        );

        settled(resolve(config, repo.path(), None));
    }

    #[test]
    fn first_matching_entry_wins() {
        let repo = repo();
        let state1 = repo.path().join("state1");
        let state2 = repo.path().join("state2");
        std::fs::create_dir_all(&state1).unwrap();
        std::fs::create_dir_all(&state2).unwrap();
        let config = write_config(
            repo.path(),
            &format!(
                "[[projects]]\n\
                 localPath = '{repo}'\n\
                 storageRoot = '{state1}'\n\
                 storageType = 'separate-dir'\n\
                 \n\
                 [[projects]]\n\
                 localPath = '{repo}'\n\
                 storageRoot = '{state2}'\n\
                 storageType = 'separate-dir'\n",
                repo = repo.path().display(),
                state1 = state1.display(),
                state2 = state2.display()
            ),
        );

        let store = settled(resolve(config, repo.path(), None));
        assert_eq!(standalone_state_dir(store), normalize_lexical(&state1));
    }

    // ── Settled: in-repo .shipsmooth/plans present, no matching config ───────

    #[test]
    fn in_repo_shipsmooth_present_no_config_settled_in_repo() {
        let repo = repo();
        std::fs::create_dir_all(repo.path().join(".shipsmooth/plans")).unwrap();
        let absent = repo.path().join("shipsmooth.toml");

        let store = settled(resolve(absent, repo.path(), None));
        assert!(matches!(store, ProjectDataStore::InRepo { .. }));
    }

    // ── the manifest.toml owned-folder marker (PB-360) ──────────────────────

    fn write_manifest(data_dir: &Path, body: &str) {
        std::fs::create_dir_all(data_dir).unwrap();
        std::fs::write(data_dir.join("manifest.toml"), body).unwrap();
    }

    const VALID_MANIFEST: &str =
        "[shipsmooth]\nkind = 'state-store'\ncli-version = '0.0.0'\n\n[manifest-schema]\nversion = '1'\n";

    #[test]
    fn a_manifest_alone_settles_in_repo_even_without_a_plans_dir() {
        let repo = repo();
        write_manifest(&repo.path().join(".shipsmooth"), VALID_MANIFEST);
        // deliberately no .shipsmooth/plans/
        let absent = repo.path().join("shipsmooth.toml");

        let store = settled(resolve(absent, repo.path(), None));
        assert!(matches!(store, ProjectDataStore::InRepo { .. }));
    }

    #[test]
    fn a_populated_plans_dir_without_a_manifest_still_settles() {
        // The backward-compat guarantee: every existing corpus predates the
        // marker. Dropping the marker must change nothing for them.
        let repo = repo();
        std::fs::create_dir_all(repo.path().join(".shipsmooth/plans")).unwrap();
        assert!(!repo.path().join(".shipsmooth/manifest.toml").exists());

        let store = settled(resolve(repo.path().join("shipsmooth.toml"), repo.path(), None));
        assert!(matches!(store, ProjectDataStore::InRepo { .. }));
    }

    #[test]
    fn an_unparseable_manifest_is_ignored_and_resolution_falls_through() {
        let repo = repo();
        write_manifest(&repo.path().join(".shipsmooth"), "not valid toml =");
        // no plans dir either -> falls all the way through to first-run
        let r = resolve(repo.path().join("shipsmooth.toml"), repo.path(), None);
        assert_eq!(needs_decision(r).situation, UndecidableSituation::CleanFirstRun);
    }

    #[test]
    fn a_config_in_repo_entry_is_settled_by_the_manifest_before_the_folder_is_provisioned() {
        let repo = repo();
        write_manifest(&repo.path().join(".shipsmooth"), VALID_MANIFEST);
        let config = write_config(
            repo.path(),
            &format!("[[projects]]\nlocalPath = '{}'\nstorageType = 'same-repo'\n", repo.path().display()),
        );

        let store = settled(resolve(config, repo.path(), None));
        assert!(matches!(store, ProjectDataStore::InRepo { .. }));
    }

    #[test]
    fn a_legacy_agents_tree_stays_unresolvable_even_with_a_manifest() {
        let repo = repo();
        std::fs::create_dir_all(repo.path().join(".agents/plans")).unwrap();
        write_manifest(&repo.path().join(".shipsmooth"), VALID_MANIFEST);

        let bad = unresolvable(resolve(repo.path().join("shipsmooth.toml"), repo.path(), None));
        assert_eq!(bad.reason, UnresolvableReason::LegacyAgentsTree);
    }

    #[test]
    fn both_in_repo_and_configured_external_config_wins() {
        let repo = repo();
        std::fs::create_dir_all(repo.path().join(".shipsmooth/plans")).unwrap();
        let storage_root = repo.path().join("state");
        std::fs::create_dir_all(&storage_root).unwrap();
        let config = write_config(
            repo.path(),
            &format!(
                "[[projects]]\n\
                 localPath = '{}'\n\
                 storageRoot = '{}'\n\
                 storageType = 'separate-dir'\n",
                repo.path().display(),
                storage_root.display()
            ),
        );

        let store = settled(resolve(config, repo.path(), None));
        assert!(matches!(store, ProjectDataStore::Standalone { .. }));
    }

    // ── storageType = "same-repo" config entry ───────────────────────────────

    #[test]
    fn embedded_entry_folder_present_settled_in_repo() {
        let repo = repo();
        std::fs::create_dir_all(repo.path().join(".shipsmooth/plans")).unwrap();
        let config = write_config(
            repo.path(),
            &format!(
                "[[projects]]\nlocalPath = '{}'\nstorageType = 'same-repo'\n",
                repo.path().display()
            ),
        );

        let store = settled(resolve(config, repo.path(), None));
        assert!(matches!(store, ProjectDataStore::InRepo { .. }));
    }

    #[test]
    fn embedded_entry_folder_missing_needs_decision_not_set_up() {
        let repo = repo();
        let config = write_config(
            repo.path(),
            &format!(
                "[[projects]]\nlocalPath = '{}'\nstorageType = 'same-repo'\n",
                repo.path().display()
            ),
        );

        let needs = needs_decision(resolve(config, repo.path(), None));
        assert_eq!(needs.situation, UndecidableSituation::InRepoNotSetUp);
        assert_eq!(needs.recommended().choice, Choice::InRepo);
    }

    #[test]
    fn embedded_entry_with_storage_root_is_malformed() {
        let repo = repo();
        let config = write_config(
            repo.path(),
            &format!(
                "[[projects]]\n\
                 localPath = '{}'\n\
                 storageType = 'same-repo'\n\
                 storageRoot = '/somewhere'\n",
                repo.path().display()
            ),
        );

        let bad = unresolvable(resolve(config, repo.path(), None));
        assert_eq!(bad.reason, UnresolvableReason::MalformedConfigEntry);
    }

    #[test]
    fn filesystem_type_with_storage_root_settled() {
        let repo = repo();
        let storage_root = repo.path().join("state");
        std::fs::create_dir_all(&storage_root).unwrap();
        let config = write_config(
            repo.path(),
            &format!(
                "[[projects]]\n\
                 localPath = '{}'\n\
                 storageType = 'separate-dir'\n\
                 storageRoot = '{}'\n",
                repo.path().display(),
                storage_root.display()
            ),
        );

        settled(resolve(config, repo.path(), None));
    }

    #[test]
    fn unknown_storage_type_is_malformed() {
        let repo = repo();
        let config = write_config(
            repo.path(),
            &format!(
                "[[projects]]\n\
                 localPath = '{}'\n\
                 storageType = 'sideways'\n\
                 storageRoot = '/somewhere'\n",
                repo.path().display()
            ),
        );

        let bad = unresolvable(resolve(config, repo.path(), None));
        assert_eq!(bad.reason, UnresolvableReason::MalformedConfigEntry);
    }

    // ── NeedsDecision ────────────────────────────────────────────────────────

    #[test]
    fn clean_first_run_needs_decision_external_recommended() {
        let repo = repo();
        let absent = repo.path().join("shipsmooth.toml");

        let needs = needs_decision(resolve(absent, repo.path(), None));
        assert_eq!(needs.situation, UndecidableSituation::CleanFirstRun);
        assert_eq!(needs.recommended().choice, Choice::External);
        // The recommended external path is a SIBLING of the repo
        // (<parent>/<repo>-shipsmooth), not a hidden ~/.local/state path — it
        // is the user's project content, pushable to its own git remote, so it
        // must be discoverable next to the repo.
        let repo_abs = normalize_lexical(repo.path());
        let expected_sibling = repo_abs.parent().unwrap().join(format!(
            "{}-shipsmooth",
            repo_abs.file_name().unwrap().to_string_lossy()
        ));
        assert_eq!(needs.recommended().proposed_path, expected_sibling);
        // in-repo is offered too, but not recommended
        assert!(needs
            .options
            .iter()
            .any(|o| o.choice == Choice::InRepo && !o.recommended));
    }

    #[test]
    fn no_matching_entry_clean_repo_needs_decision() {
        let repo = repo();
        let config = write_config(
            repo.path(),
            "[[projects]]\n\
             localPath = '/some/other/path'\n\
             storageRoot = '/state'\n\
             storageType = 'separate-dir'\n",
        );

        needs_decision(resolve(config, repo.path(), None));
    }

    #[test]
    fn remote_url_mismatch_treated_as_no_match() {
        let repo = repo();
        let config = write_config(
            repo.path(),
            &format!(
                "[[projects]]\n\
                 remoteUrl = 'https://github.com/org/repo.git'\n\
                 localPath = '{}'\n\
                 storageRoot = '/state'\n\
                 storageType = 'separate-dir'\n",
                repo.path().display()
            ),
        );

        needs_decision(resolve(config, repo.path(), Some("https://github.com/org/OTHER.git")));
    }

    #[test]
    fn config_filesystem_dir_missing_needs_decision_recreate() {
        let repo = repo();
        let storage_root = repo.path().join("gone"); // never created
        let config = write_config(
            repo.path(),
            &format!(
                "[[projects]]\n\
                 localPath = '{}'\n\
                 storageRoot = '{}'\n\
                 storageType = 'separate-dir'\n",
                repo.path().display(),
                storage_root.display()
            ),
        );

        let needs = needs_decision(resolve(config, repo.path(), None));
        assert_eq!(needs.situation, UndecidableSituation::ConfigDirMissing);
        assert_eq!(needs.recommended().choice, Choice::RecreateMissingDir);
        assert_eq!(needs.recommended().proposed_path, normalize_lexical(&storage_root));
    }

    // ── Unresolvable ─────────────────────────────────────────────────────────

    #[test]
    fn legacy_agents_tree_unresolvable() {
        let repo = repo();
        std::fs::create_dir_all(repo.path().join(".agents/plans")).unwrap();
        let absent = repo.path().join("shipsmooth.toml");

        let bad = unresolvable(resolve(absent, repo.path(), None));
        assert_eq!(bad.reason, UnresolvableReason::LegacyAgentsTree);
        // message (sourced from the reason) names both folders so the user can rename by hand
        assert!(bad.message().contains(".agents") && bad.message().contains(".shipsmooth"));
        assert!(bad.cause.is_none(), "an anticipated reason carries no throwable cause");
    }

    #[test]
    fn matched_entry_without_storage_type_unresolvable_malformed() {
        let repo = repo();
        let config = write_config(
            repo.path(),
            &format!("[[projects]]\nlocalPath = '{}'\n", repo.path().display()),
        );

        let bad = unresolvable(resolve(config, repo.path(), None));
        assert_eq!(bad.reason, UnresolvableReason::MalformedConfigEntry);
    }

    // ── Bad config file is tolerated, not fatal (plan-87) ────────────────────
    // A failed `store init` write can leave a 0-byte / garbage shipsmooth.toml
    // behind. A stray or truncated global config must NOT wedge resolution: it
    // is treated as "no usable config" and resolution falls through to the
    // filesystem.

    #[test]
    fn unparseable_config_falls_through_to_filesystem() {
        let repo = repo();
        let config = write_config(repo.path(), "this is = = not valid toml [[[");

        // Clean repo (no .shipsmooth/plans) => first-run decision, not Unresolvable.
        let needs = needs_decision(resolve(config, repo.path(), None));
        assert_eq!(needs.situation, UndecidableSituation::CleanFirstRun);
    }

    #[test]
    fn empty_config_file_falls_through_to_filesystem() {
        let repo = repo();
        let config = write_config(repo.path(), ""); // the 0-byte file the defect leaves behind

        needs_decision(resolve(config, repo.path(), None));
    }

    #[test]
    fn empty_config_with_in_repo_state_stays_settled() {
        // The repo already has valid in-repo state; a poisoned global config must not break it.
        let repo = repo();
        std::fs::create_dir_all(repo.path().join(".shipsmooth/plans")).unwrap();
        let config = write_config(repo.path(), "");

        let store = settled(resolve(config, repo.path(), None));
        assert!(matches!(store, ProjectDataStore::InRepo { .. }));
    }
}
