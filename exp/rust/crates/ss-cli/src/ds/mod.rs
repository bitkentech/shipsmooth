//! Data-store resolution: where a project's shipsmooth state lives.

pub mod atomic;
pub mod config;
pub mod config_file;
pub mod config_writer;
pub mod legacy_guard;
pub mod manifest;
pub mod paths;
pub mod resolution;
pub mod resolver;
pub mod schema_config;
pub mod store;
