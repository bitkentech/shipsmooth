//! Port of the Java `io.bitken.ss.conf` package: where shipsmooth data lives.
//!
//! `ResolvedStateRoot` is the validation token minted once by whoever resolved
//! the state root; `ShipsmoothDataLocator` is the single source of truth for
//! path construction under it. The Java unchecked exceptions
//! (`InaccessibleRootException`, `StateRootUnsettledException`) are the
//! `ss_core::Error::{InaccessibleRoot, StateRootUnsettled}` variants.

mod locator;
mod state_root;

pub use locator::ShipsmoothDataLocator;
pub use state_root::ResolvedStateRoot;
