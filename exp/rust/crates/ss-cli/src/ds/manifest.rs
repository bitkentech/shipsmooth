//! The owned-folder marker (PB-360): `manifest.toml` at the data root.
//!
//! Small and write-only. `store init` stamps it into a folder it creates; the
//! resolver reads it as a recorded fact that shipsmooth owns the folder,
//! rather than inferring ownership from a `plans/` subdirectory. Unlike
//! `shipsmooth.toml` there is no upsert and no unknown-but-valid content to
//! preserve, so it is emitted from a fixed template and parsed leniently.

use std::path::Path;

use serde::Deserialize;

use crate::ds::atomic::write_atomically;

/// The `kind` value marking a shipsmooth-owned state folder.
pub const KIND_STATE_STORE: &str = "state-store";
/// The manifest's own schema version.
pub const SCHEMA_VERSION: &str = "1";

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    pub shipsmooth: ShipsmoothSection,
    /// Modeled (not just skipped) so `deny_unknown_fields` still accepts a
    /// real manifest; only tests read it back.
    #[serde(rename = "manifest-schema")]
    #[allow(dead_code)]
    pub manifest_schema: Option<SchemaRef>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ShipsmoothSection {
    pub kind: String,
    #[serde(rename = "cli-version")]
    #[allow(dead_code)]
    pub cli_version: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SchemaRef {
    #[allow(dead_code)]
    pub version: Option<String>,
}

impl Manifest {
    /// True when this marker names a shipsmooth-owned state folder.
    pub fn is_state_store(&self) -> bool {
        self.shipsmooth.kind == KIND_STATE_STORE
    }

    /// Lenient read: a missing, unreadable, or unparseable file is `None` —
    /// "no usable marker" — never a hard failure (the same spirit as the
    /// resolver's `shipsmooth.toml` read).
    pub fn read(path: &Path) -> Option<Manifest> {
        let text = std::fs::read_to_string(path).ok()?;
        toml_edit::de::from_str(&text).ok()
    }

    /// The manifest body this CLI build stamps into a folder it creates.
    pub fn current_body() -> String {
        render(env!("CARGO_PKG_VERSION"))
    }

    /// Atomically (over)write the current manifest at `path`.
    pub fn write(path: &Path) -> std::io::Result<()> {
        write_atomically(path, Self::current_body().as_bytes())
    }
}

/// The fixed template — single-quoted literals and one blank line after each
/// table, matching the `shipsmooth.toml` emitter's layout so the two files
/// look alike. `cli_version` is a plain semver string, never containing a
/// quote, so a literal is always safe.
fn render(cli_version: &str) -> String {
    format!(
        "[shipsmooth]\n\
         kind = '{KIND_STATE_STORE}'\n\
         cli-version = '{cli_version}'\n\
         \n\
         [manifest-schema]\n\
         version = '{SCHEMA_VERSION}'\n\
         \n"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn current_body_has_the_expected_shape_and_the_build_version() {
        let body = Manifest::current_body();
        assert_eq!(
            body,
            format!(
                "[shipsmooth]\nkind = 'state-store'\ncli-version = '{}'\n\n\
                 [manifest-schema]\nversion = '1'\n\n",
                env!("CARGO_PKG_VERSION")
            )
        );
    }

    #[test]
    fn write_then_read_round_trips_and_classifies_as_a_state_store() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("sub").join("manifest.toml");

        Manifest::write(&path).unwrap();

        let read = Manifest::read(&path).expect("freshly written manifest must parse");
        assert!(read.is_state_store());
        assert_eq!(read.shipsmooth.cli_version.as_deref(), Some(env!("CARGO_PKG_VERSION")));
        assert_eq!(read.manifest_schema.unwrap().version.as_deref(), Some("1"));
    }

    #[test]
    fn write_is_idempotent_and_leaves_no_temp_litter() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("manifest.toml");

        Manifest::write(&path).unwrap();
        Manifest::write(&path).unwrap();

        assert_eq!(std::fs::read_to_string(&path).unwrap(), Manifest::current_body());
        let entries: Vec<_> = std::fs::read_dir(tmp.path()).unwrap().map(|e| e.unwrap().file_name()).collect();
        assert_eq!(entries, ["manifest.toml"], "no temp file left behind");
    }

    #[test]
    fn a_missing_or_unparseable_marker_reads_as_none() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("manifest.toml");
        assert!(Manifest::read(&path).is_none(), "missing file");

        std::fs::write(&path, "this is not toml =").unwrap();
        assert!(Manifest::read(&path).is_none(), "unparseable file");

        // A foreign but valid TOML file: no [shipsmooth] table -> None (the
        // field is required).
        std::fs::write(&path, "[other]\nx = 1\n").unwrap();
        assert!(Manifest::read(&path).is_none(), "foreign toml");
    }

    #[test]
    fn a_marker_with_an_unknown_kind_still_parses_but_is_not_a_state_store() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("manifest.toml");
        std::fs::write(&path, "[shipsmooth]\nkind = 'something-else'\n").unwrap();

        let read = Manifest::read(&path).expect("valid structure, unknown kind");
        assert!(!read.is_state_store());
    }
}
