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
    if local_path.join(DATA_DIR).join(PLANS_SUBDIR).is_dir() {
        return DataStoreResolution::Settled(ProjectDataStore::InRepo {
            repo_root: local_path.to_path_buf(),
        });
    }
    DataStoreResolution::NeedsDecision(NeedsDecision {
        situation: UndecidableSituation::InRepoNotSetUp,
        options: vec![DecisionOption {
            choice: Choice::InRepo,
            proposed_path: local_path.join(DATA_DIR),
            recommended: true,
        }],
    })
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
    if local_path.join(DATA_DIR).join(PLANS_SUBDIR).is_dir() {
        return DataStoreResolution::Settled(ProjectDataStore::InRepo {
            repo_root: local_path.to_path_buf(),
        });
    }
    clean_first_run(local_path)
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
    //! plan-106 Task 4 de-risk: the branch table's core paths — clean first
    //! run with the sibling proposal, the separate-dir config branches, and
    //! the unresolvable cases. The full `ProjectDataStoreResolverTest` port
    //! lands in hardening.

    use super::*;

    fn resolver_for(dir: &Path) -> ProjectDataStoreResolver {
        ProjectDataStoreResolver::new(dir.join("shipsmooth.toml"))
    }

    fn needs_decision(r: DataStoreResolution) -> NeedsDecision {
        match r {
            DataStoreResolution::NeedsDecision(n) => n,
            _ => panic!("expected NeedsDecision"),
        }
    }

    #[test]
    fn clean_first_run_offers_external_sibling_recommended_then_in_repo() {
        let tmp = tempfile::tempdir().unwrap();
        let repo = tmp.path().join("proj");
        std::fs::create_dir(&repo).unwrap();

        let needs = needs_decision(resolver_for(tmp.path()).resolve(&repo, None));

        assert_eq!(needs.situation, UndecidableSituation::CleanFirstRun);
        assert_eq!(needs.options.len(), 2);
        let external = needs.recommended();
        assert_eq!(external.choice, Choice::External);
        assert_eq!(external.proposed_path, tmp.path().join("proj-shipsmooth"));
        let in_repo = &needs.options[1];
        assert_eq!(in_repo.choice, Choice::InRepo);
        assert_eq!(in_repo.proposed_path, repo.join(".shipsmooth"));
        assert!(!in_repo.recommended);
    }

    #[test]
    fn separate_dir_entry_settles_on_existing_root_and_offers_recreate_on_missing() {
        let tmp = tempfile::tempdir().unwrap();
        let repo = tmp.path().join("proj");
        std::fs::create_dir(&repo).unwrap();
        let state = tmp.path().join("proj-shipsmooth");
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
        let resolver = resolver_for(tmp.path());

        // Root missing → offer to recreate exactly the configured path.
        let needs = needs_decision(resolver.resolve(&repo, None));
        assert_eq!(needs.situation, UndecidableSituation::ConfigDirMissing);
        assert_eq!(needs.recommended().choice, Choice::RecreateMissingDir);
        assert_eq!(needs.recommended().proposed_path, state);

        // Root present → settled standalone on that root.
        std::fs::create_dir(&state).unwrap();
        match resolver.resolve(&repo, None) {
            DataStoreResolution::Settled(ProjectDataStore::Standalone {
                repo_root,
                state_dir,
            }) => {
                assert_eq!(repo_root, repo);
                assert_eq!(state_dir, state);
            }
            _ => panic!("expected Settled(Standalone)"),
        }
    }

    #[test]
    fn legacy_tree_and_malformed_entry_are_unresolvable() {
        let tmp = tempfile::tempdir().unwrap();

        // Legacy .agents/plans/ tree, no config → LEGACY_AGENTS_TREE.
        let legacy = tmp.path().join("legacy");
        std::fs::create_dir_all(legacy.join(".agents/plans")).unwrap();
        match resolver_for(tmp.path()).resolve(&legacy, None) {
            DataStoreResolution::Unresolvable(u) => {
                assert_eq!(u.reason, UnresolvableReason::LegacyAgentsTree)
            }
            _ => panic!("expected Unresolvable"),
        }

        // A same-repo entry carrying a storageRoot → MALFORMED_CONFIG_ENTRY.
        let repo = tmp.path().join("proj");
        std::fs::create_dir(&repo).unwrap();
        std::fs::write(
            tmp.path().join("shipsmooth.toml"),
            format!(
                "[[projects]]\nlocalPath = '{}'\nstorageRoot = '/somewhere'\nstorageType = 'same-repo'\n",
                repo.display()
            ),
        )
        .unwrap();
        match resolver_for(tmp.path()).resolve(&repo, None) {
            DataStoreResolution::Unresolvable(u) => {
                assert_eq!(u.reason, UnresolvableReason::MalformedConfigEntry)
            }
            _ => panic!("expected Unresolvable"),
        }
    }
}
