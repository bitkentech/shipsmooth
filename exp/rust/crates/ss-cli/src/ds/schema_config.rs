//! The `[toml-schema]` values this CLI build emits into `shipsmooth.toml`.
//!
//! Port of the Java `SchemaConfig` template (plan-91 Task 4). The Java build
//! bakes `${schema.location}` per variant as a version-pinned releases URL. The
//! shipsmooth-beta build deliberately does NOT pin (plan-115, 05 §4 option 2): a
//! `v<beta-version>` tag never carries `dist/schemas/`, and both products write
//! this key into the same `shipsmooth.toml`, so the beta points at the unversioned
//! `releases`-branch copy. Re-pin at promotion.

/// The TOML Schema version advertised in the emitted `[toml-schema]` table.
pub const SCHEMA_VERSION: &str = "1.0.0";

/// The `location` URI emitted in `[toml-schema]` for this build.
pub const SCHEMA_LOCATION: &str =
    "https://raw.githubusercontent.com/bitkentech/shipsmooth/releases/dist/schemas/shipsmooth.tosd";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn location_is_the_unversioned_releases_branch_url() {
        // plan-115 (05 §4 option 2): during the beta period both products write this
        // key into the same shipsmooth.toml, so the beta emits an unversioned URL that
        // always resolves, instead of a v<beta-version> tag that never carries a schema.
        assert_eq!(
            SCHEMA_LOCATION,
            "https://raw.githubusercontent.com/bitkentech/shipsmooth/releases/dist/schemas/shipsmooth.tosd"
        );
        assert!(!SCHEMA_LOCATION.contains(env!("CARGO_PKG_VERSION")));
    }
}
