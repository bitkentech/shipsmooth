//! Hidden `probe` subcommand (plan-102 Task 6, footprint spike).
//!
//! Exists so size/memory measurements are honest: it genuinely exercises
//! every runtime crate — quick-xml + the model (corpus round-trip), regex
//! (markdown parser), unicode-normalization (slugify), time (timestamp),
//! toml_edit (read-modify-write), serde + serde_json (this report) — so the
//! linker cannot dead-strip a dependency the real port will need. Not a user
//! command; hidden from help and free to change shape between measurements.

use std::path::PathBuf;

use serde::Serialize;
use ss_core::model::PlanTasks;
use ss_core::plan::{parse_tasks, slugify};

#[derive(clap::Args)]
pub struct ProbeArgs {
    /// Directory of plan-tasks XML files to round-trip.
    #[arg(long)]
    dir: PathBuf,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Report {
    fixtures: usize,
    round_tripped: usize,
    slug: String,
    markdown_tasks: usize,
    toml: String,
    timestamp: String,
}

const MARKDOWN_SNIPPET: &str = "\
### Task 1: Probe first task [Low]\n\nBody.\n\n### Task 2: Probe second task [High]\n\nBody.\n";

pub fn run(args: &ProbeArgs) -> i32 {
    match report(args) {
        Ok(r) => {
            println!("{}", serde_json::to_string(&r).expect("report serializes"));
            0
        }
        Err(e) => {
            eprintln!("probe: {e}");
            1
        }
    }
}

fn report(args: &ProbeArgs) -> ss_core::Result<Report> {
    let mut xml_files: Vec<PathBuf> = std::fs::read_dir(&args.dir)?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.extension().is_some_and(|x| x == "xml"))
        .collect();
    xml_files.sort();

    let mut round_tripped = 0;
    for path in &xml_files {
        let plan = PlanTasks::load(path)?;
        // Idempotent write: parse(serialize) serializes back identically.
        let first = plan.to_xml();
        if PlanTasks::parse(&first)?.to_xml() == first {
            round_tripped += 1;
        }
    }

    let format =
        time::macros::format_description!("[year]-[month]-[day]T[hour]:[minute]:[second]");
    let timestamp = time::OffsetDateTime::now_utc()
        .format(format)
        .map_err(|e| ss_core::Error::Scaffold(e.to_string()))?;

    Ok(Report {
        fixtures: xml_files.len(),
        round_tripped,
        slug: slugify("Café déjà vu"),
        markdown_tasks: parse_tasks(MARKDOWN_SNIPPET).len(),
        toml: edited_toml(),
        timestamp,
    })
}

fn edited_toml() -> String {
    let mut doc: toml_edit::DocumentMut =
        "[probe]\n# layout survives\nruns = 1\n".parse().expect("static TOML parses");
    doc["probe"]["runs"] = toml_edit::value(2);
    doc.to_string()
}
