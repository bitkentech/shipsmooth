//! ss-core — Rust port of the shipsmooth `core` Gradle module (plan-102).
//!
//! Module map mirrors the Java packages:
//! `model` ← generated `io.bitken.ss.jaxb`, `plan` ← `io.bitken.ss.svc.plan`,
//! `gw` ← `io.bitken.ss.gw`, `conf` ← `io.bitken.ss.conf`.

pub mod error;
pub mod model;
pub mod plan;

pub use error::Error;

/// Crate-wide result alias; replaces the Java checked-exception signatures.
pub type Result<T> = std::result::Result<T, Error>;
