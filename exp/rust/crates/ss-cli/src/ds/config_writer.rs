//! Writes (upserts) a project entry into the user's `shipsmooth.toml`.
//!
//! Port of the Java `ConfigWriter`, rebuilt on `toml_edit` (the Java
//! `ArrayOfTablesTomlEmitter` — a hand-rolled workaround for Jackson's
//! single-line array serialisation, plan-90 — is deleted, not transliterated:
//! `toml_edit` emits multi-line `[[projects]]` blocks natively). The emitted
//! bytes match the Java emitter: stable key order, single-quoted literal
//! strings where possible, one blank line after each block.
//!
//! The counterpart to `ProjectDataStoreResolver`'s read path. Records a
//! project's chosen state location keyed on `(localPath, remoteUrl)`: a
//! matching entry is replaced (idempotent), otherwise a new one is appended.
//! The config file and its parent directory are created if absent. Paths are
//! written verbatim — never hash-derived.

use std::path::{Path, PathBuf};

use crate::ds::paths::normalize_lexical;
use crate::ds::schema_config;

pub struct ConfigWriter {
    /// Injected config-file location, as for the resolver.
    config_file: PathBuf,
    /// The `[toml-schema] location` to emit; `None` means version only
    /// (spec-valid: `location` is optional).
    schema_location: Option<String>,
}

impl ConfigWriter {
    /// Production shape: emits the build's baked schema location.
    pub fn new(config_file: PathBuf) -> ConfigWriter {
        ConfigWriter { config_file, schema_location: Some(schema_config::SCHEMA_LOCATION.to_string()) }
    }

    /// Inject the schema `location` to emit (tests only).
    #[cfg(test)]
    pub fn with_schema_location(config_file: PathBuf, schema_location: Option<String>) -> ConfigWriter {
        ConfigWriter { config_file, schema_location }
    }

    /// Upsert a `separate-dir` entry recording the chosen `storageRoot`.
    pub fn write_external(
        &self,
        local_path: &Path,
        remote_url: Option<&str>,
        storage_root: &Path,
    ) -> std::io::Result<()> {
        self.upsert(
            local_path,
            remote_url,
            Some(&normalize_lexical(storage_root).to_string_lossy()),
            "separate-dir",
        )
    }

    /// Upsert a `same-repo` entry (no `storageRoot`).
    pub fn write_in_repo(&self, local_path: &Path, remote_url: Option<&str>) -> std::io::Result<()> {
        self.upsert(local_path, remote_url, None, "same-repo")
    }

    fn upsert(
        &self,
        local_path: &Path,
        remote_url: Option<&str>,
        storage_root: Option<&str>,
        storage_type: &str,
    ) -> std::io::Result<()> {
        let local = normalize_lexical(local_path).to_string_lossy().into_owned();
        let mut doc = self.read_or_empty()?;

        ensure_schema_ref(&mut doc, self.schema_location.as_deref());
        let projects = projects_tables(&mut doc);
        remove_same_project(projects, &local, remote_url);

        let mut entry = toml_edit::Table::new();
        if let Some(url) = remote_url {
            entry["remoteUrl"] = literal(url);
        }
        entry["localPath"] = literal(&local);
        if let Some(root) = storage_root {
            entry["storageRoot"] = literal(root);
        }
        entry["storageType"] = literal(storage_type);
        projects.push(entry);

        self.write_atomically(&render(&doc))
    }

    /// Serialize to a sibling temp file first, then atomically rename it into
    /// place. A failed write leaves only the discarded temp file behind —
    /// never a truncated 0-byte `shipsmooth.toml` that would wedge every
    /// subsequent resolve (plan-87).
    fn write_atomically(&self, content: &str) -> std::io::Result<()> {
        let dir = match self.config_file.parent() {
            Some(parent) => {
                std::fs::create_dir_all(parent)?;
                parent
            }
            None => Path::new("."),
        };
        let mut tmp = tempfile::NamedTempFile::new_in(dir)?;
        std::io::Write::write_all(&mut tmp, content.as_bytes())?;
        tmp.persist(&self.config_file).map_err(|e| e.error)?;
        Ok(())
    }

    /// Read the existing config document, or start empty when the file does
    /// not exist. Unlike the resolver's lenient read path, the write path is
    /// strict: an unparseable existing config is an error, not something to
    /// silently overwrite. Parsing as a `DocumentMut` (not into the model)
    /// preserves unknown-but-valid content verbatim across the upsert.
    fn read_or_empty(&self) -> std::io::Result<toml_edit::DocumentMut> {
        if !self.config_file.exists() {
            return Ok(toml_edit::DocumentMut::new());
        }
        std::fs::read_to_string(&self.config_file)?
            .parse::<toml_edit::DocumentMut>()
            .map_err(std::io::Error::other)
    }
}

/// Add the `[toml-schema]` table if absent: `version` always, `location` only
/// when this build has one.
fn ensure_schema_ref(doc: &mut toml_edit::DocumentMut, schema_location: Option<&str>) {
    if doc.contains_key("toml-schema") {
        return;
    }
    let mut schema = toml_edit::Table::new();
    schema["version"] = literal(schema_config::SCHEMA_VERSION);
    if let Some(location) = schema_location {
        schema["location"] = literal(location);
    }
    doc.insert("toml-schema", toml_edit::Item::Table(schema));
}

/// The `[[projects]]` array of tables, created if absent.
fn projects_tables(doc: &mut toml_edit::DocumentMut) -> &mut toml_edit::ArrayOfTables {
    if !doc.contains_key("projects") {
        doc.insert("projects", toml_edit::Item::ArrayOfTables(toml_edit::ArrayOfTables::new()));
    }
    doc["projects"].as_array_of_tables_mut().expect("projects is an array of tables")
}

/// Drop any entry describing the same project — `(localPath, remoteUrl)`
/// match, remote compared blank-insensitively because TOML round-tripping
/// turns an absent value into an empty string.
fn remove_same_project(projects: &mut toml_edit::ArrayOfTables, local: &str, remote_url: Option<&str>) {
    let target_remote = remote_url.unwrap_or("").trim().to_string();
    projects.retain(|t| {
        let entry_local = t.get("localPath").and_then(|v| v.as_str()).unwrap_or("");
        let entry_remote = t.get("remoteUrl").and_then(|v| v.as_str()).unwrap_or("").trim();
        !(normalize_str_path(entry_local) == local && entry_remote == target_remote)
    });
}

fn normalize_str_path(raw: &str) -> String {
    if raw.is_empty() {
        return String::new();
    }
    normalize_lexical(Path::new(raw)).to_string_lossy().into_owned()
}

/// A TOML value in the Java emitter's quoting style: a literal single-quoted
/// string when the value has no single quote or control character (matching
/// Jackson's prior style for our paths and `git@…` URLs), else a basic
/// double-quoted string with toml_edit's standard escapes.
fn literal(value: &str) -> toml_edit::Item {
    let has_control = value.chars().any(|c| c < '\u{20}');
    let v = if !value.contains('\'') && !has_control {
        format!("'{value}'").parse::<toml_edit::Value>().expect("literal string is valid TOML")
    } else {
        toml_edit::Value::from(value)
    };
    toml_edit::Item::Value(v)
}

/// Render with the Java emitter's block layout: one blank line after each
/// block, including the last.
fn render(doc: &toml_edit::DocumentMut) -> String {
    let mut out = String::new();
    for block in doc.to_string().split("\n\n") {
        let block = block.trim_end_matches('\n');
        if block.is_empty() {
            continue;
        }
        out.push_str(block);
        out.push_str("\n\n");
    }
    out
}

#[cfg(test)]
mod tests {
    //! Full port of the Java `ConfigWriterTest` (round trips, schema table,
    //! idempotent upsert, atomic write) plus the byte-parity layout test. The
    //! Java atomicity test injects an exploding emitter; Rust's render cannot
    //! fail, so the same guarantee — a failed write never truncates the
    //! existing config, no temp litter — is pinned via an unwritable config
    //! dir and the strict-read failure instead.

    use super::*;
    use crate::ds::resolution::{DataStoreResolution, UndecidableSituation};
    use crate::ds::resolver::ProjectDataStoreResolver;
    use crate::ds::store::ProjectDataStore;

    fn resolve(config: &Path, repo: &Path) -> DataStoreResolution {
        ProjectDataStoreResolver::new(config.to_path_buf()).resolve(repo, None)
    }

    fn settled_standalone_root(r: DataStoreResolution) -> PathBuf {
        match r {
            DataStoreResolution::Settled(ProjectDataStore::Standalone { state_dir, .. }) => {
                state_dir
            }
            _ => panic!("expected Settled(Standalone)"),
        }
    }

    #[test]
    fn write_external_then_resolver_reads_standalone() {
        let tmp = tempfile::tempdir().unwrap();
        let config = tmp.path().join("shipsmooth.toml");
        let repo = tmp.path().join("repo");
        let state = tmp.path().join("state");
        std::fs::create_dir(&repo).unwrap();
        std::fs::create_dir(&state).unwrap();

        ConfigWriter::new(config.clone()).write_external(&repo, None, &state).unwrap();

        assert!(config.exists(), "config file must be created");
        assert_eq!(
            settled_standalone_root(resolve(&config, &repo)),
            normalize_lexical(&state)
        );
    }

    #[test]
    fn emits_injected_schema_location() {
        let tmp = tempfile::tempdir().unwrap();
        let config = tmp.path().join("shipsmooth.toml");
        let repo = tmp.path().join("repo");
        std::fs::create_dir(&repo).unwrap();

        let loc = "https://example.test/shipsmooth.tosd";
        ConfigWriter::with_schema_location(config.clone(), Some(loc.to_string()))
            .write_in_repo(&repo, None)
            .unwrap();

        let written = std::fs::read_to_string(&config).unwrap();
        assert!(written.contains("[toml-schema]"), "{written}");
        assert!(written.contains(&format!("location = '{loc}'")), "{written}");
    }

    #[test]
    fn omits_location_when_not_injected() {
        let tmp = tempfile::tempdir().unwrap();
        let config = tmp.path().join("shipsmooth.toml");
        let repo = tmp.path().join("repo");
        std::fs::create_dir(&repo).unwrap();

        // No schema location → [toml-schema] carries version only, no location key.
        ConfigWriter::with_schema_location(config.clone(), None)
            .write_in_repo(&repo, None)
            .unwrap();

        let written = std::fs::read_to_string(&config).unwrap();
        assert!(written.contains("[toml-schema]"), "{written}");
        assert!(written.contains("version = "), "{written}");
        assert!(!written.contains("location"), "no location key when none injected:\n{written}");
    }

    #[test]
    fn write_in_repo_then_resolver_sees_in_repo_entry() {
        let tmp = tempfile::tempdir().unwrap();
        let config = tmp.path().join("shipsmooth.toml");
        let repo = tmp.path().join("repo");
        std::fs::create_dir(&repo).unwrap();

        ConfigWriter::new(config.clone()).write_in_repo(&repo, None).unwrap();

        // No .shipsmooth/plans yet → an in-repo entry is recognised but not yet set up.
        match resolve(&config, &repo) {
            DataStoreResolution::NeedsDecision(needs) => {
                assert_eq!(needs.situation, UndecidableSituation::InRepoNotSetUp)
            }
            _ => panic!("expected NeedsDecision"),
        }

        // Once the folder exists, the same entry resolves settled in-repo.
        std::fs::create_dir_all(repo.join(".shipsmooth/plans")).unwrap();
        match resolve(&config, &repo) {
            DataStoreResolution::Settled(ProjectDataStore::InRepo { .. }) => {}
            _ => panic!("expected Settled(InRepo)"),
        }
    }

    #[test]
    fn written_bytes_match_the_java_emitter_layout() {
        let tmp = tempfile::tempdir().unwrap();
        let config = tmp.path().join("shipsmooth.toml");
        let repo = tmp.path().join("proj");
        let state = tmp.path().join("proj-shipsmooth");
        std::fs::create_dir(&repo).unwrap();

        ConfigWriter::with_schema_location(
            config.clone(),
            Some("https://example.test/shipsmooth.tosd".to_string()),
        )
        .write_external(&repo, Some("git@github.com:org/proj.git"), &state)
        .unwrap();

        // The exact block layout the Java ArrayOfTablesTomlEmitter produced:
        // stable key order, single-quoted literals, blank line after each block.
        let expected = format!(
            "[toml-schema]\n\
             version = '1.0.0'\n\
             location = 'https://example.test/shipsmooth.tosd'\n\
             \n\
             [[projects]]\n\
             remoteUrl = 'git@github.com:org/proj.git'\n\
             localPath = '{}'\n\
             storageRoot = '{}'\n\
             storageType = 'separate-dir'\n\
             \n",
            repo.display(),
            state.display()
        );
        assert_eq!(std::fs::read_to_string(&config).unwrap(), expected);
    }

    #[test]
    fn upsert_is_idempotent_replaces_matching_entry() {
        let tmp = tempfile::tempdir().unwrap();
        let config = tmp.path().join("shipsmooth.toml");
        let repo = tmp.path().join("repo");
        let state_a = tmp.path().join("stateA");
        let state_b = tmp.path().join("stateB");
        std::fs::create_dir(&repo).unwrap();
        std::fs::create_dir(&state_a).unwrap();
        std::fs::create_dir(&state_b).unwrap();

        let writer = ConfigWriter::new(config.clone());
        writer.write_external(&repo, None, &state_a).unwrap();
        writer.write_external(&repo, None, &state_b).unwrap(); // same project, new dir

        assert_eq!(
            settled_standalone_root(resolve(&config, &repo)),
            normalize_lexical(&state_b),
            "second upsert for the same project must replace, not duplicate"
        );
    }

    #[test]
    fn distinct_projects_coexist() {
        let tmp = tempfile::tempdir().unwrap();
        let config = tmp.path().join("shipsmooth.toml");
        let repo1 = tmp.path().join("repo1");
        let repo2 = tmp.path().join("repo2");
        let state1 = tmp.path().join("s1");
        let state2 = tmp.path().join("s2");
        for d in [&repo1, &repo2, &state1, &state2] {
            std::fs::create_dir(d).unwrap();
        }

        let writer = ConfigWriter::new(config.clone());
        writer.write_external(&repo1, None, &state1).unwrap();
        writer.write_external(&repo2, None, &state2).unwrap();

        assert_eq!(settled_standalone_root(resolve(&config, &repo1)), normalize_lexical(&state1));
        assert_eq!(settled_standalone_root(resolve(&config, &repo2)), normalize_lexical(&state2));
    }

    // ── Atomic write: a failed write never truncates the config (plan-87) ────

    #[cfg(unix)]
    #[test]
    fn failed_write_leaves_existing_config_intact_and_no_temp_litter() {
        use std::os::unix::fs::PermissionsExt;

        let tmp = tempfile::tempdir().unwrap();
        let config_dir = tmp.path().join("conf");
        std::fs::create_dir(&config_dir).unwrap();
        let config = config_dir.join("shipsmooth.toml");
        let repo = tmp.path().join("repo");
        let good = tmp.path().join("good");
        std::fs::create_dir(&repo).unwrap();
        std::fs::create_dir(&good).unwrap();

        // First write a valid config the normal way.
        ConfigWriter::new(config.clone()).write_external(&repo, None, &good).unwrap();
        let before = std::fs::read_to_string(&config).unwrap();
        assert!(!before.trim().is_empty(), "precondition: a valid non-empty config exists");

        // Now make the write fail mid-flight: the config dir refuses new files,
        // so the temp-file creation blows up before the rename can happen.
        std::fs::set_permissions(&config_dir, std::fs::Permissions::from_mode(0o555)).unwrap();
        let repo2 = tmp.path().join("repo2");
        let other = tmp.path().join("other");
        std::fs::create_dir(&repo2).unwrap();
        std::fs::create_dir(&other).unwrap();
        let result = ConfigWriter::new(config.clone()).write_external(&repo2, None, &other);
        std::fs::set_permissions(&config_dir, std::fs::Permissions::from_mode(0o755)).unwrap();

        assert!(result.is_err(), "write into an unwritable dir must fail");
        // The original config must survive byte-for-byte — never a truncated 0-byte file.
        assert_eq!(
            std::fs::read_to_string(&config).unwrap(),
            before,
            "a failed write must not corrupt the existing config"
        );
        // And no temp file may be left behind in the config directory.
        let litter: Vec<_> = std::fs::read_dir(&config_dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .filter(|name| name != "shipsmooth.toml")
            .collect();
        assert!(litter.is_empty(), "failed write left temp litter: {litter:?}");
    }

    #[test]
    fn unparseable_existing_config_fails_the_write_and_is_preserved() {
        // The write path is strict where the read path is lenient: silently
        // replacing a corrupt config would destroy entries for every other
        // project recorded in it.
        let tmp = tempfile::tempdir().unwrap();
        let config = tmp.path().join("shipsmooth.toml");
        let repo = tmp.path().join("repo");
        std::fs::create_dir(&repo).unwrap();
        std::fs::write(&config, "this is = = not valid toml [[[").unwrap();

        let result = ConfigWriter::new(config.clone()).write_external(&repo, None, &repo);

        assert!(result.is_err(), "upsert over an unparseable config must fail");
        assert_eq!(
            std::fs::read_to_string(&config).unwrap(),
            "this is = = not valid toml [[[",
            "the corrupt config must be left for the user to inspect, not overwritten"
        );
    }

    #[test]
    fn upsert_preserves_other_entries_verbatim() {
        // DocumentMut round-trips the existing file, so entries for other
        // projects keep their exact bytes across an upsert.
        let tmp = tempfile::tempdir().unwrap();
        let config = tmp.path().join("shipsmooth.toml");
        let repo = tmp.path().join("repo");
        std::fs::create_dir(&repo).unwrap();
        let existing = "[[projects]]\nlocalPath = '/somewhere/else'\nstorageType = 'same-repo'\n";
        std::fs::write(&config, existing).unwrap();

        ConfigWriter::new(config.clone()).write_in_repo(&repo, None).unwrap();

        let written = std::fs::read_to_string(&config).unwrap();
        assert!(written.contains("localPath = '/somewhere/else'"), "{written}");
        assert!(written.contains(&format!("localPath = '{}'", repo.display())), "{written}");
    }
}
