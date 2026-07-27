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

#[cfg(test)]
mod tests {
    //! Port of the Java `DefaultConfigFileLocatorTest`.

    use super::*;

    // XDG_CONFIG_HOME wins on any platform when set.
    #[test]
    fn xdg_config_home_takes_precedence() {
        let result =
            config_file_for(Some("/xdg/config"), Some(r"C:\Users\me\AppData\Roaming"), "/home/me");
        assert_eq!(result, PathBuf::from("/xdg/config/shipsmooth/shipsmooth.toml"));
    }

    // Windows: no XDG, %APPDATA% set → roaming app data.
    #[test]
    fn app_data_used_when_no_xdg() {
        let result = config_file_for(None, Some(r"C:\Users\me\AppData\Roaming"), r"C:\Users\me");
        assert_eq!(
            result,
            PathBuf::from(r"C:\Users\me\AppData\Roaming").join("shipsmooth/shipsmooth.toml")
        );
    }

    // POSIX: no XDG, no %APPDATA% → ~/.config.
    #[test]
    fn posix_default_when_no_xdg_or_app_data() {
        let result = config_file_for(None, None, "/home/me");
        assert_eq!(result, PathBuf::from("/home/me/.config/shipsmooth/shipsmooth.toml"));
    }

    // Blank env values are treated as unset.
    #[test]
    fn blank_env_values_fall_through() {
        let result = config_file_for(Some("  "), Some(""), "/home/me");
        assert_eq!(result, PathBuf::from("/home/me/.config/shipsmooth/shipsmooth.toml"));
    }

    // locate() reads the real environment; whatever config home it picks, the
    // file is always shipsmooth/shipsmooth.toml.
    #[test]
    fn locate_always_ends_with_shipsmooth_toml() {
        let result = locate();
        assert!(
            result.ends_with("shipsmooth/shipsmooth.toml"),
            "locate() must resolve to shipsmooth/shipsmooth.toml; was: {}",
            result.display()
        );
    }
}
