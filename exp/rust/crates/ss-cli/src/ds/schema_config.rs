//! The `[toml-schema]` values this CLI build emits into `shipsmooth.toml`.
//!
//! Port of the Java `SchemaConfig` template (plan-91 Task 4). The Java build
//! bakes `${schema.location}` per variant; the prod value is the version-pinned
//! releases URL, which Rust derives from the workspace version at compile time
//! (the workspace version is the single source of truth, so the pinned URL can
//! never drift from the binary that emits it).

/// The TOML Schema version advertised in the emitted `[toml-schema]` table.
pub const SCHEMA_VERSION: &str = "1.0.0";

/// The `location` URI emitted in `[toml-schema]` for this build.
pub const SCHEMA_LOCATION: &str = concat!(
    "https://raw.githubusercontent.com/bitkentech/shipsmooth/v",
    env!("CARGO_PKG_VERSION"),
    "/dist/schemas/shipsmooth.tosd"
);

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
