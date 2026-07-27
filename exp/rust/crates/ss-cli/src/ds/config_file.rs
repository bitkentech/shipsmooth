//! Locates the user's `shipsmooth.toml` under the platform config home.
//!
//! Port of the Java `DefaultConfigFileLocator`. Precedence:
//! `XDG_CONFIG_HOME` (explicit override, any platform) → `%APPDATA%`
//! (Windows roaming app data) → `~/.config` (POSIX default).

use std::path::PathBuf;

/// The real config-file location, from the process environment.
pub fn locate() -> PathBuf {
    config_file_for(
        std::env::var("XDG_CONFIG_HOME").ok().as_deref(),
        std::env::var("APPDATA").ok().as_deref(),
        &std::env::var("HOME").unwrap_or_default(),
    )
}

/// Pure config-home selection — exposed for testing the per-platform branches.
fn config_file_for(xdg_config_home: Option<&str>, app_data: Option<&str>, user_home: &str) -> PathBuf {
    let config_home = match (blank_to_none(xdg_config_home), blank_to_none(app_data)) {
        (Some(xdg), _) => PathBuf::from(xdg),
        (None, Some(appdata)) => PathBuf::from(appdata),
        (None, None) => PathBuf::from(user_home).join(".config"),
    };
    config_home.join("shipsmooth/shipsmooth.toml")
}

fn blank_to_none(value: Option<&str>) -> Option<&str> {
    value.filter(|v| !v.trim().is_empty())
}
