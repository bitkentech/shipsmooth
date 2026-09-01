//! Atomic file replacement: write a sibling temp file, then rename it into
//! place. A failed write leaves only the discarded temp file behind — never a
//! truncated or half-written target (the plan-87 guarantee for
//! `shipsmooth.toml`, reused for `manifest.toml`).

use std::path::Path;

/// (Over)write `path` atomically with `content`. The parent directory is
/// created if absent.
pub fn write_atomically(path: &Path, content: &[u8]) -> std::io::Result<()> {
    let dir = match path.parent() {
        Some(parent) => {
            std::fs::create_dir_all(parent)?;
            parent
        }
        None => Path::new("."),
    };
    let mut tmp = tempfile::NamedTempFile::new_in(dir)?;
    std::io::Write::write_all(&mut tmp, content)?;
    tmp.persist(path).map_err(|e| e.error)?;
    Ok(())
}
