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
    /// Modeled (not just skipped) so `deny_unknown_fields` still accepts real
    /// configs; only the writer and tests consume it.
    #[serde(rename = "toml-schema")]
    #[allow(dead_code)]
    pub toml_schema: Option<TomlSchemaRef>,
    #[serde(default)]
    pub projects: Vec<ProjectEntry>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TomlSchemaRef {
    #[allow(dead_code)]
    pub version: Option<String>,
    #[allow(dead_code)]
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
        assert!(
            schema.location.as_deref().unwrap().ends_with("/shipsmooth.tosd"),
            "[toml-schema] location must reference the schema file"
        );

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

    #[test]
    fn java_written_multi_line_config_round_trips_through_matching() {
        // Read-side half of Java's MultiLineTomlConfigIntegrationTest: a config
        // the Java ConfigWriter wrote as multi-line [[projects]] blocks must
        // read back and match by its own localPath. The write side lands with
        // ConfigWriter in Task 7.
        let config = StandaloneConfig::parse(&fixture("settled-same-repo"))
            .expect("Java-written fixture config must parse");
        let configured_path = config.projects[0].local_path.clone().unwrap();

        let entry = config
            .matching_entry(Path::new(&configured_path), None)
            .expect("Java-written entry must match its own localPath");
        assert_eq!(entry.storage_type.as_deref(), Some("same-repo"));
        assert_eq!(entry.storage_root, None, "same-repo entries carry no storageRoot");
    }

    #[test]
    fn malformed_entries_still_parse_classification_is_the_resolvers_job() {
        // Leniency covers *unparseable* files only. A well-formed file whose
        // entry is semantically malformed (bad or missing storageType, a
        // same-repo entry carrying a storageRoot) must parse and hand the
        // entry through — classifying it is ProjectDataStoreResolver's job.
        for scenario in [
            "malformed-bad-type",
            "malformed-missing-type",
            "malformed-same-repo-with-root",
        ] {
            let config = StandaloneConfig::parse(&fixture(scenario))
                .unwrap_or_else(|| panic!("{scenario} must parse"));
            assert_eq!(config.projects.len(), 1, "{scenario} must keep its entry");
        }

        let bad_type = StandaloneConfig::parse(&fixture("malformed-bad-type")).unwrap();
        assert_eq!(bad_type.projects[0].storage_type.as_deref(), Some("cloud"));
    }

    #[test]
    fn unknown_keys_make_the_config_unusable_matching_jackson() {
        // Jackson's default FAIL_ON_UNKNOWN_PROPERTIES makes the Java read
        // path treat a config with unknown keys as unusable (caught, then
        // "no usable config"); deny_unknown_fields pins the same behaviour.
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("future.toml");
        std::fs::write(&file, "[[projects]]\nlocalPath = '/x'\nfutureKey = 'y'\n").unwrap();
        assert!(StandaloneConfig::parse(&file).is_none());
    }
}
