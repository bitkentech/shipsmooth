//! Pure plan logic — port of `io.bitken.ss.svc.plan` (plan-102 Task 4).
//! The warm-up slice: no I/O beyond a directory listing, behaviour pinned by
//! tests ported verbatim from the Java suite plus the golden transcripts.

mod markdown;
mod numbers;
mod slugs;
mod stub;
mod summary;

pub use markdown::{parse_tasks, parse_with_diagnostics, slice_task_section, Diagnostic, ParsedTask};
pub use numbers::PlanNumbers;
pub use slugs::{branch_name, slugify};
pub use stub::stub_markdown;
pub use summary::summary;
