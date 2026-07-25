//! The user's `shipsmooth.toml` — model, lenient parse, and entry matching.
//!
//! Port of the Java `StandaloneConfig` plus the resolver's private read path
//! (`parseConfig` / `matchingEntry`). Reading is lenient per plan-87: an
//! unreadable or unparseable config resolves as "no usable config" and falls
//! through to filesystem detection — a stray or truncated (0-byte) global
//! config must never poison an otherwise-valid project.

use std::path::Path;

use serde::Deserialize;

use crate::ds::paths::normalize_lexical;

/// Root of `~/.config/shipsmooth/shipsmooth.toml`.
#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StandaloneConfig {
    /// The `[toml-schema]` table — a reference to the TOML Schema definition.
    #[serde(rename = "toml-schema")]
    pub toml_schema: Option<TomlSchemaRef>,
    #[serde(default)]
    pub projects: Vec<ProjectEntry>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TomlSchemaRef {
    pub version: Option<String>,
    pub location: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectEntry {
    pub remote_url: Option<String>,
    pub local_path: Option<String>,
    /// The `separate-dir` backend's state-tree root; absent for `same-repo`.
    pub storage_root: Option<String>,
    /// `"same-repo"` or `"separate-dir"`; anything else is a malformed entry.
    pub storage_type: Option<String>,
}

impl StandaloneConfig {
    /// Read the config file, tolerating an unusable one (plan-87 leniency):
    /// a missing, unreadable, or unparseable file is `None` — "no usable
    /// config" — never a hard failure.
    pub fn parse(config_file: &Path) -> Option<StandaloneConfig> {
        let text = std::fs::read_to_string(config_file).ok()?;
        toml_edit::de::from_str(&text).ok()
    }

    /// The entry for this project, matched by the `(localPath, remoteUrl)`
    /// pair: the lexically-normalised local path must match, and when both
    /// sides carry a remote URL those must match too (either side absent
    /// matches on path alone).
    pub fn matching_entry(
        &self,
        local_path: &Path,
        remote_url: Option<&str>,
    ) -> Option<&ProjectEntry> {
        let local = normalize_lexical(local_path);
        self.projects.iter().find(|entry| {
            let entry_path = match entry.local_path.as_deref() {
                Some(p) if !p.is_empty() => normalize_lexical(Path::new(p)),
                // An entry without a localPath can never match (Java compares
                // against "" which never equals an absolute path).
                _ => return false,
            };
            if entry_path != local {
                return false;
            }
            match (remote_url, entry.remote_url.as_deref()) {
                (Some(query), Some(configured)) => query == configured,
                _ => true,
            }
        })
    }
}

#[cfg(test)]
mod tests {
    //! plan-106 Task 3 de-risk: the config read path's core logic — real
    //! fixture parse, plan-87 leniency, and lexical (localPath, remoteUrl)
    //! matching.

    use super::*;
    use std::path::PathBuf;

    fn fixture(scenario: &str) -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../fixtures/transcripts/store")
            .join(scenario)
            .join("shipsmooth.toml")
    }

    #[test]
    fn parses_the_settled_separate_dir_fixture() {
        let config = StandaloneConfig::parse(&fixture("settled-separate-dir"))
            .expect("fixture config must parse");

        let schema = config.toml_schema.as_ref().expect("[toml-schema] table");
        assert_eq!(schema.version.as_deref(), Some("1.0.0"));

        assert_eq!(config.projects.len(), 1);
        let entry = &config.projects[0];
        assert_eq!(entry.storage_type.as_deref(), Some("separate-dir"));
        assert!(entry.local_path.as_deref().unwrap().ends_with("/proj"));
        assert!(entry.storage_root.as_deref().unwrap().ends_with("/proj-shipsmooth"));
        assert_eq!(entry.remote_url, None);
    }

    #[test]
    fn unusable_config_is_no_usable_config_never_a_hard_failure() {
        let dir = tempfile::tempdir().unwrap();

        // Unparseable garbage → None (plan-87: fall through to filesystem).
        let garbage = dir.path().join("garbage.toml");
        std::fs::write(&garbage, "projects = [ not toml at all").unwrap();
        assert!(StandaloneConfig::parse(&garbage).is_none());

        // A 0-byte config (failed `store init` write) must not poison the
        // project: whatever shape parse gives back, no entry may match.
        let empty = dir.path().join("empty.toml");
        std::fs::write(&empty, "").unwrap();
        let matched = StandaloneConfig::parse(&empty)
            .and_then(|c| c.matching_entry(Path::new("/any/repo"), None).map(|_| ()));
        assert!(matched.is_none());

        // Missing file → None.
        assert!(StandaloneConfig::parse(&dir.path().join("absent.toml")).is_none());
    }

    #[test]
    fn matching_is_lexical_on_local_path_and_filters_on_remote_url() {
        let toml = r#"
[[projects]]
localPath = '/repos/other'
storageType = 'same-repo'

[[projects]]
remoteUrl = 'git@github.com:user/proj.git'
localPath = '/repos/proj/../proj/.'
storageRoot = '/repos/proj-shipsmooth'
storageType = 'separate-dir'
"#;
        let config: StandaloneConfig = toml_edit::de::from_str(toml).unwrap();

        // Lexical normalisation: the messy configured path matches the clean
        // query (and would also match a symlinked or not-yet-created path,
        // which canonicalize() could not).
        let hit = config
            .matching_entry(Path::new("/repos/proj"), Some("git@github.com:user/proj.git"))
            .expect("lexically-equal paths must match");
        assert_eq!(hit.storage_root.as_deref(), Some("/repos/proj-shipsmooth"));

        // Both sides carry a remote and they differ → no match.
        assert!(config
            .matching_entry(Path::new("/repos/proj"), Some("git@github.com:someone/else.git"))
            .is_none());

        // Query without a remote matches on path alone.
        assert!(config.matching_entry(Path::new("/repos/proj"), None).is_some());
    }
}
