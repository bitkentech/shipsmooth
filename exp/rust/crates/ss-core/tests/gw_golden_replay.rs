//! Plan-107 Task 8: golden replay of the Java CLI's mutation sequence.
//!
//! `fixtures/generate.sh` drives the **real Java `shipsmooth` binary** through
//! `plan init` and seventeen mutations, capturing `plan-42-tasks.xml` after
//! each one. This test replays the equivalent calls through the Rust
//! `TaskStore` and byte-diffs every intermediate file against what Java wrote.
//!
//! It is the independent check the unit tests cannot be: those were written by
//! the same porter who wrote the implementation and inherit their assumptions,
//! whereas these bytes came out of the Java binary before any Rust existed.
//!
//! Timestamps are handled by pinning the clock to the value the Java run
//! recorded for that step, so the comparison stays byte-exact rather than
//! normalising the timestamps away. Steps that should record no timestamp run
//! under a 1999 sentinel clock: were one to leak in, it would show up in the
//! diff instead of passing silently.

use std::cell::Cell;
use std::path::PathBuf;
use std::rc::Rc;

use ss_core::conf::ShipsmoothDataLocator;
use ss_core::gw::{GitTags, TaskStore};
use ss_core::model::PlanTasks;
use ss_core::plan::{self, ParsedTask};
use time::macros::datetime;
use time::OffsetDateTime;

/// The plan narrative `generate.sh` feeds to `plan init`, verbatim.
const PLAN_42_MD: &str = r#"# Plan 42 — gw mutation fixture

## Tasks

### Task 1: Parse the input [High]

Core slice.

### Task 2: Write the output [Medium]

*Depends-on: 1*

Serialisation slice.

### Task 3: Wire the flow

*Depends-on: 1, 2*

No risk tag (empty risk value), multi-id depends-on.
"#;

/// Clock value for steps that must not record a timestamp. Any leak lands a
/// 1999 date in the output and fails the byte comparison.
const NO_TIMESTAMP: OffsetDateTime = datetime!(1999-12-31 23:59:59.999 UTC);

#[test]
fn replays_the_java_cli_mutation_sequence_byte_for_byte() {
    let repo = tempfile::tempdir().unwrap();
    let locator = ShipsmoothDataLocator::in_repo(repo.path()).unwrap();

    // `plan init` derives the version from git tags; an untagged directory
    // degrades to v1, which is what the fixture run recorded.
    let plan_version = GitTags::new(repo.path()).get_plan_version(42);
    assert_eq!(plan_version, "plan-42-v1", "no tags in a fresh dir derives v1");

    // `plan init --tasks-from plan-42.md`: parse the narrative, then generate.
    let markdown_path = locator.plan_markdown_file(42);
    std::fs::create_dir_all(markdown_path.parent().unwrap()).unwrap();
    std::fs::write(&markdown_path, PLAN_42_MD).unwrap();
    let specs = plan::parse_tasks(PLAN_42_MD);
    assert_eq!(specs.len(), 3, "the narrative declares three tasks");

    let clock = Rc::new(Cell::new(NO_TIMESTAMP));
    let ticking = Rc::clone(&clock);
    let store = TaskStore::with_clock(locator, move || ticking.get());

    clock.set(datetime!(2026-08-06 18:15:26.599 +05:30));
    let plan = store.generate_plan_tasks(42, &plan_version, &specs).unwrap();

    let mut replay = Replay { store, clock, plan, plan_id: 42 };
    replay.check("step-00-init.xml");

    // The seventeen mutations, in generate.sh's order and with its arguments.
    replay.step("step-01-status-in-progress.xml", NO_TIMESTAMP, |s, p| {
        s.update_task_status(p, 1, "in-progress").unwrap()
    });
    replay.step(
        "step-02-comment-escapables.xml",
        datetime!(2026-08-06 18:15:27.968 +05:30),
        |s, p| {
            s.add_comment(p, 1, "Special chars: & < > \" ' and unicode: héllo 🚀").unwrap()
        },
    );
    replay.step("step-03-set-commit.xml", NO_TIMESTAMP, |s, p| {
        s.set_commit(p, 1, "0123456789abcdef0123456789abcdef01234567").unwrap()
    });
    replay.step("step-04-status-de-risked.xml", NO_TIMESTAMP, |s, p| {
        s.update_task_status(p, 1, "de-risked").unwrap()
    });
    replay.step(
        "step-05-deviation-minor.xml",
        datetime!(2026-08-06 18:15:30.060 +05:30),
        |s, p| s.add_deviation(p, 1, "minor", "Split parsing into two passes").unwrap(),
    );
    replay.step("step-06-status-agent-coded.xml", NO_TIMESTAMP, |s, p| {
        s.update_task_status(p, 1, "agent-coded").unwrap()
    });
    replay.step(
        "step-07-deviation-major.xml",
        datetime!(2026-08-06 18:15:31.339 +05:30),
        |s, p| {
            s.add_deviation(p, 2, "major", "Format spec contradicts implementation").unwrap()
        },
    );
    replay.step("step-08-status-needs-triage.xml", NO_TIMESTAMP, |s, p| {
        s.update_task_status(p, 2, "needs-triage").unwrap()
    });
    replay.step("step-09-add-task-with-deps.xml", NO_TIMESTAMP, |s, p| {
        assert_eq!(s.add_task(p, &spec("Added after init", "low", "1,3"), "plan-42-v1").unwrap(), 4);
    });
    replay.step("step-10-add-task-minimal.xml", NO_TIMESTAMP, |s, p| {
        assert_eq!(s.add_task(p, &spec("Bare addition", "", ""), "plan-42-v1").unwrap(), 5);
    });
    replay.step("step-11-status-abandoned.xml", NO_TIMESTAMP, |s, p| {
        s.update_task_status(p, 3, "abandoned").unwrap()
    });
    replay.step(
        "step-12-comment-appends.xml",
        datetime!(2026-08-06 18:15:34.797 +05:30),
        |s, p| s.add_comment(p, 1, "Second comment appends").unwrap(),
    );
    replay.step(
        "step-13-update-message.xml",
        datetime!(2026-08-06 18:15:35.490 +05:30),
        |s, p| s.project_update(p, None, None, Some("Mid-plan checkpoint")).unwrap(),
    );
    replay.step(
        "step-14-update-blocked.xml",
        datetime!(2026-08-06 18:15:36.202 +05:30),
        |s, p| {
            s.project_update(p, None, Some(true), Some("Blocked on format decision")).unwrap()
        },
    );
    replay.step(
        "step-15-update-in-review.xml",
        datetime!(2026-08-06 18:15:36.867 +05:30),
        |s, p| {
            s.project_update(p, Some("in-review"), None, Some("Ready for review")).unwrap()
        },
    );
    replay.step("step-16-status-closed.xml", NO_TIMESTAMP, |s, p| {
        s.update_task_status(p, 1, "closed").unwrap()
    });
    replay.step(
        "step-17-update-complete.xml",
        datetime!(2026-08-06 18:15:38.241 +05:30),
        |s, p| s.project_update(p, Some("complete"), None, Some("Done")).unwrap(),
    );

    // The replayed document must also survive a reload unchanged.
    let reloaded = replay.store.load_plan(42).unwrap();
    assert_eq!(reloaded.to_xml(), fixture("step-17-update-complete.xml"));
    assert_eq!(reloaded.tasks.len(), 5);
    assert_eq!(reloaded.metadata.status, "complete");
}

/// Replays one mutation at a time, persisting through the real write path and
/// comparing the file on disk — not just the in-memory rendering — with what
/// the Java CLI left behind.
struct Replay {
    store: TaskStore,
    clock: Rc<Cell<OffsetDateTime>>,
    plan: PlanTasks,
    plan_id: u32,
}

impl Replay {
    fn step(
        &mut self,
        fixture_name: &str,
        now: OffsetDateTime,
        mutate: impl FnOnce(&TaskStore, &mut PlanTasks),
    ) {
        self.clock.set(now);
        mutate(&self.store, &mut self.plan);
        self.check(fixture_name);
    }

    fn check(&self, fixture_name: &str) {
        self.store.save_plan(self.plan_id, &self.plan).unwrap();
        let written =
            std::fs::read_to_string(self.store.plan_tasks_file(self.plan_id)).unwrap();
        assert_eq!(written, fixture(fixture_name), "diverged at {fixture_name}");
    }
}

fn spec(name: &str, risk: &str, depends_on: &str) -> ParsedTask {
    ParsedTask { id: 0, name: name.into(), risk: risk.into(), depends_on: depends_on.into() }
}

fn fixture(name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/xml/gw").join(name);
    std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}
