//! Port of `io.bitken.ss.gw.TaskStore`: the typed persistence façade for
//! `plan-{N}-tasks.xml`, over the canonical locator layout. The file-level
//! semantics Java kept here — the atomic `.tmp`-and-rename write and the
//! reader's rename-race retry — live on [`PlanTasks::load`]/[`PlanTasks::save`]
//! in the model; this façade binds them to plan ids.

use std::path::PathBuf;

use time::OffsetDateTime;

use crate::conf::ShipsmoothDataLocator;
use crate::gw::xml_time;
use crate::gw::xml_time::Clock;
use crate::model::{
    Comment, Deviation, DeviationKind, Metadata, PlanStatus, PlanTasks, RawElement, RawNode, Task,
    TaskStatus, Update,
};
use crate::plan::ParsedTask;
use crate::{Error, Result};

pub struct TaskStore {
    locator: ShipsmoothDataLocator,
    clock: Clock,
}

impl TaskStore {
    pub fn new(locator: ShipsmoothDataLocator) -> TaskStore {
        TaskStore::with_clock(locator, xml_time::system_now)
    }

    /// Injectable-clock constructor (plan-107 design decision): mutation
    /// timestamps come from `clock` instead of the system clock, so tests
    /// and the golden-replay harness can pin exact lexical values.
    pub fn with_clock(
        locator: ShipsmoothDataLocator,
        clock: impl Fn() -> OffsetDateTime + 'static,
    ) -> TaskStore {
        let clock: Clock = Box::new(clock);
        TaskStore { locator, clock }
    }

    /// The `xs:dateTime` stamp mutations record, drawn from the clock seam.
    fn now_xml_date_time(&self) -> String {
        xml_time::xml_date_time((self.clock)())
    }

    /// Canonical XML task file for this plan, under the resolved layout.
    pub fn plan_tasks_file(&self, plan_id: u32) -> PathBuf {
        self.locator.plan_tasks_file(plan_id)
    }

    /// True when the XML task file exists for this plan.
    pub fn plan_tasks_file_exists(&self, plan_id: u32) -> bool {
        self.plan_tasks_file(plan_id).exists()
    }

    /// Loads the plan's XML by id using the canonical layout, with the
    /// reader's rename-race retry (see [`PlanTasks::load`]).
    pub fn load_plan(&self, plan_id: u32) -> Result<PlanTasks> {
        PlanTasks::load(&self.plan_tasks_file(plan_id))
    }

    /// Saves the plan's XML by id, atomically (see [`PlanTasks::save`]).
    pub fn save_plan(&self, plan_id: u32, plan: &PlanTasks) -> Result<()> {
        plan.save(&self.plan_tasks_file(plan_id))
    }

    /// Port of `generatePlanTasks`: a fresh plan document with one pending
    /// task per spec. Task ids come from the spec (the markdown headings),
    /// not from the position — Java writes `t.id()` through verbatim.
    ///
    /// `depends-on` is applied in a second pass, as Java does, so every task
    /// element exists before any of them is looked up by id.
    pub fn generate_plan_tasks(
        &self,
        plan_num: u32,
        plan_version: &str,
        specs: &[ParsedTask],
    ) -> Result<PlanTasks> {
        let now = (self.clock)();
        let mut plan = PlanTasks {
            plan: plan_num.to_string(),
            plan_version: plan_version.to_string(),
            metadata: Metadata {
                backlog_issue: String::new(),
                status: "active".to_string(),
                created: xml_time::xml_date(now.date()),
                extensions: Vec::new(),
            },
            tasks: specs
                .iter()
                .map(|t| new_pending_task(t.id, &t.name, &t.risk, plan_version))
                .collect(),
            project_updates: vec![Update {
                timestamp: xml_time::xml_date_time(now),
                message: "Plan initialised.".to_string(),
                blocked: Some("false".to_string()),
            }],
        };
        for spec in specs {
            if !spec.depends_on.trim().is_empty() {
                self.set_depends_on(&mut plan, spec.id, &spec.depends_on)?;
            }
        }
        Ok(plan)
    }

    /// Port of `addTask`: appends a pending task, ignoring the spec's own id
    /// in favour of `max(existing ids) + 1`. Returns the assigned id.
    pub fn add_task(
        &self,
        plan: &mut PlanTasks,
        spec: &ParsedTask,
        plan_version: &str,
    ) -> Result<u32> {
        let next_id = next_task_id(plan);
        plan.tasks.push(new_pending_task(next_id, &spec.name, &spec.risk, plan_version));
        if !spec.depends_on.trim().is_empty() {
            self.set_depends_on(plan, next_id, &spec.depends_on)?;
        }
        Ok(next_id)
    }

    /// Port of `getDependsOn`: the raw `<depends-on>` text, or `""` when the
    /// element is absent. An unknown task id is an error, as in Java, where
    /// `findTask` throws before the element lookup happens.
    pub fn get_depends_on(&self, plan: &PlanTasks, task_id: u32) -> Result<String> {
        Ok(find_task(plan, task_id)?.depends_on())
    }

    /// Port of `setDependsOn`: sets or replaces the task's `<depends-on>`
    /// extension element. A blank value removes it.
    pub fn set_depends_on(&self, plan: &mut PlanTasks, task_id: u32, value: &str) -> Result<()> {
        let task = find_task_mut(plan, task_id)?;
        task.extensions.retain(|e| e.name != "depends-on");
        let trimmed = value.trim();
        if !trimmed.is_empty() {
            task.extensions.push(RawElement {
                name: "depends-on".to_string(),
                attrs: Vec::new(),
                children: vec![RawNode::Text(trimmed.to_string())],
            });
        }
        Ok(())
    }

    /// Port of `getTaskName`: the task's display name, falling back to the
    /// stringified id — for an unnamed task *and* for one that is not there.
    /// Java resolves this through a stream default rather than `findTask`, so
    /// unlike the mutations it never fails.
    pub fn get_task_name(&self, plan: &PlanTasks, task_id: u32) -> String {
        match find_task(plan, task_id) {
            Ok(task) if !task.name.is_empty() => task.name.clone(),
            _ => task_id.to_string(),
        }
    }

    /// Port of `updateTaskStatus`. The token is validated on the way in, as
    /// Java's `TaskStatusType.fromValue` does.
    pub fn update_task_status(
        &self,
        plan: &mut PlanTasks,
        task_id: u32,
        status: &str,
    ) -> Result<()> {
        let status: TaskStatus = status.parse()?;
        find_task_mut(plan, task_id)?.status = status.to_string();
        Ok(())
    }

    /// Port of `setCommit`.
    pub fn set_commit(&self, plan: &mut PlanTasks, task_id: u32, commit: &str) -> Result<()> {
        find_task_mut(plan, task_id)?.commit = commit.to_string();
        Ok(())
    }

    /// Port of `addComment`: appends a timestamped comment to the task.
    pub fn add_comment(&self, plan: &mut PlanTasks, task_id: u32, message: &str) -> Result<()> {
        let timestamp = self.now_xml_date_time();
        find_task_mut(plan, task_id)?
            .comments
            .push(Comment { timestamp, message: message.to_string() });
        Ok(())
    }

    /// Port of `addDeviation`: appends a timestamped, typed deviation.
    pub fn add_deviation(
        &self,
        plan: &mut PlanTasks,
        task_id: u32,
        kind: &str,
        message: &str,
    ) -> Result<()> {
        let kind: DeviationKind = kind.parse()?;
        let timestamp = self.now_xml_date_time();
        find_task_mut(plan, task_id)?.deviations.push(Deviation {
            kind: kind.to_string(),
            timestamp,
            message: message.to_string(),
        });
        Ok(())
    }

    /// Port of `projectUpdate`: appends a plan-level update and, when
    /// `status` is given, rewrites the plan's own status. A missing `blocked`
    /// records `false` and a missing message records the empty string —
    /// Java's null-coalescing, not an omitted element.
    pub fn project_update(
        &self,
        plan: &mut PlanTasks,
        status: Option<&str>,
        blocked: Option<bool>,
        message: Option<&str>,
    ) -> Result<()> {
        if let Some(status) = status {
            let status: PlanStatus = status.parse()?;
            plan.metadata.status = status.to_string();
        }
        plan.project_updates.push(Update {
            timestamp: self.now_xml_date_time(),
            message: message.unwrap_or_default().to_string(),
            blocked: Some(blocked.unwrap_or(false).to_string()),
        });
        Ok(())
    }
}

/// Port of `newPendingTask`: pending, empty commit, `created-from` set, empty
/// comment/deviation containers and no extensions.
fn new_pending_task(id: u32, name: &str, risk: &str, plan_version: &str) -> Task {
    Task {
        id: id.to_string(),
        risk: risk.to_string(),
        status: "pending".to_string(),
        name: name.to_string(),
        commit: String::new(),
        created_from: plan_version.to_string(),
        closed_at_version: String::new(),
        comments: Vec::new(),
        deviations: Vec::new(),
        extensions: Vec::new(),
    }
}

/// Port of `nextTaskId`: `max(existing ids) + 1`, or 1 on an empty plan.
/// Ids that do not parse are skipped rather than failing the append — Java's
/// `BigInteger` ids cannot be non-numeric, but this model stores lexicals.
fn next_task_id(plan: &PlanTasks) -> u32 {
    plan.tasks.iter().filter_map(|t| t.id_number().ok()).max().unwrap_or(0) + 1
}

fn find_task(plan: &PlanTasks, task_id: u32) -> Result<&Task> {
    plan.tasks
        .iter()
        .find(|t| t.id_number().ok() == Some(task_id))
        .ok_or(Error::TaskNotFound(task_id))
}

/// Port of `findTask`: the first task with this id, or the Java error text.
fn find_task_mut(plan: &mut PlanTasks, task_id: u32) -> Result<&mut Task> {
    plan.tasks
        .iter_mut()
        .find(|t| t.id_number().ok() == Some(task_id))
        .ok_or(Error::TaskNotFound(task_id))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store_in(repo: &std::path::Path) -> TaskStore {
        TaskStore::new(ShipsmoothDataLocator::in_repo(repo).unwrap())
    }

    fn gw_fixture(name: &str) -> String {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/xml/gw").join(name);
        std::fs::read_to_string(path).unwrap()
    }

    #[test]
    fn save_plan_lands_on_the_canonical_layout_and_loads_back() {
        let repo = tempfile::tempdir().unwrap();
        let store = store_in(repo.path());
        let plan = PlanTasks::parse(&gw_fixture("step-00-init.xml")).unwrap();

        assert!(!store.plan_tasks_file_exists(42));
        store.save_plan(42, &plan).unwrap();
        assert!(store.plan_tasks_file_exists(42));
        assert_eq!(
            store.plan_tasks_file(42),
            repo.path().join(".shipsmooth/plans/plan-42-tasks.xml")
        );

        let loaded = store.load_plan(42).unwrap();
        assert_eq!(loaded.plan, "42");
        assert_eq!(loaded.plan_version, "plan-42-v1");
        assert_eq!(loaded.tasks.len(), 3);
    }

    #[test]
    fn java_written_file_survives_the_store_write_path_byte_identical() {
        let repo = tempfile::tempdir().unwrap();
        let store = store_in(repo.path());
        let input = gw_fixture("step-17-update-complete.xml");

        store.save_plan(42, &PlanTasks::parse(&input).unwrap()).unwrap();
        assert_eq!(std::fs::read_to_string(store.plan_tasks_file(42)).unwrap(), input);
    }

    #[test]
    fn load_plan_reports_a_missing_file() {
        let repo = tempfile::tempdir().unwrap();
        assert!(store_in(repo.path()).load_plan(7).is_err());
    }

    // ---- plan-107 Task 4 de-risk: fresh-element construction ----

    /// The instant the Task 1 fixture corpus was initialised under.
    fn pinned_store(repo: &std::path::Path) -> TaskStore {
        store_at(repo, time::macros::datetime!(2026-08-06 18:15:26.599 +05:30))
    }

    /// A store whose clock is pinned to one fixture step's own timestamp, so
    /// the mutation it performs renders byte-identically to that step's XML.
    fn store_at(repo: &std::path::Path, now: time::OffsetDateTime) -> TaskStore {
        let locator = ShipsmoothDataLocator::in_repo(repo).unwrap();
        TaskStore::with_clock(locator, move || now)
    }

    /// Applies one mutation to `from` and asserts it reproduces `to` exactly.
    /// Every step here mirrors a real `shipsmooth` CLI invocation captured in
    /// fixtures/generate.sh, so the corpus is the spec for both.
    fn assert_step(
        from: &str,
        to: &str,
        now: time::OffsetDateTime,
        mutate: impl FnOnce(&TaskStore, &mut PlanTasks),
    ) {
        let repo = tempfile::tempdir().unwrap();
        let store = store_at(repo.path(), now);
        let mut plan = PlanTasks::parse(&gw_fixture(from)).unwrap();
        mutate(&store, &mut plan);
        assert_eq!(plan.to_xml(), gw_fixture(to), "{from} -> {to}");
    }

    fn spec(id: u32, name: &str, risk: &str, depends_on: &str) -> ParsedTask {
        ParsedTask {
            id,
            name: name.into(),
            risk: risk.into(),
            depends_on: depends_on.into(),
        }
    }

    #[test]
    fn generate_plan_tasks_renders_byte_identical_to_the_java_fixture() {
        let repo = tempfile::tempdir().unwrap();
        let store = pinned_store(repo.path());

        let plan = store
            .generate_plan_tasks(
                42,
                "plan-42-v1",
                &[
                    spec(1, "Parse the input", "high", ""),
                    spec(2, "Write the output", "medium", "1"),
                    spec(3, "Wire the flow", "", "1,2"),
                ],
            )
            .unwrap();

        assert_eq!(plan.to_xml(), gw_fixture("step-00-init.xml"));
    }

    #[test]
    fn add_task_appends_max_id_plus_one_rendering_like_java() {
        let repo = tempfile::tempdir().unwrap();
        let store = pinned_store(repo.path());
        let mut plan = PlanTasks::parse(&gw_fixture("step-08-status-needs-triage.xml")).unwrap();

        let assigned = store
            .add_task(&mut plan, &spec(0, "Added after init", "low", "1,3"), "plan-42-v1")
            .unwrap();

        assert_eq!(assigned, 4, "id is max(existing) + 1, not the spec's own id");
        assert_eq!(plan.to_xml(), gw_fixture("step-09-add-task-with-deps.xml"));
    }

    #[test]
    fn depends_on_is_set_replaced_and_removed_in_the_extension_slot() {
        let repo = tempfile::tempdir().unwrap();
        let store = pinned_store(repo.path());
        let mut plan = PlanTasks::parse(&gw_fixture("step-00-init.xml")).unwrap();

        assert_eq!(store.get_depends_on(&plan, 2).unwrap(), "1");
        store.set_depends_on(&mut plan, 2, "1,3").unwrap();
        assert_eq!(store.get_depends_on(&plan, 2).unwrap(), "1,3");
        // Replacing must not leave a second <depends-on> behind.
        assert_eq!(plan.tasks[1].extensions.len(), 1);

        store.set_depends_on(&mut plan, 2, "  ").unwrap();
        assert_eq!(store.get_depends_on(&plan, 2).unwrap(), "");
        assert!(plan.tasks[1].extensions.is_empty(), "blank removes the element");

        // A task with no <depends-on> gains one at the end of the slot.
        store.set_depends_on(&mut plan, 1, " 2 ").unwrap();
        assert_eq!(store.get_depends_on(&plan, 1).unwrap(), "2");

        assert_eq!(
            store.set_depends_on(&mut plan, 99, "1").unwrap_err().to_string(),
            "Task 99 not found"
        );
    }

    #[test]
    fn update_task_status_replaces_the_status_token() {
        assert_step(
            "step-00-init.xml",
            "step-01-status-in-progress.xml",
            time::macros::datetime!(2026-08-06 18:15:27.000 +05:30),
            |store, plan| store.update_task_status(plan, 1, "in-progress").unwrap(),
        );
    }

    #[test]
    fn add_comment_appends_an_entry_escaping_markup_like_jaxb() {
        assert_step(
            "step-01-status-in-progress.xml",
            "step-02-comment-escapables.xml",
            time::macros::datetime!(2026-08-06 18:15:27.968 +05:30),
            |store, plan| {
                store
                    .add_comment(plan, 1, "Special chars: & < > \" ' and unicode: héllo 🚀")
                    .unwrap()
            },
        );
    }

    #[test]
    fn add_comment_appends_after_an_existing_one() {
        assert_step(
            "step-11-status-abandoned.xml",
            "step-12-comment-appends.xml",
            time::macros::datetime!(2026-08-06 18:15:34.797 +05:30),
            |store, plan| store.add_comment(plan, 1, "Second comment appends").unwrap(),
        );
    }

    #[test]
    fn set_commit_writes_the_sha_into_the_commit_element() {
        assert_step(
            "step-02-comment-escapables.xml",
            "step-03-set-commit.xml",
            time::macros::datetime!(2026-08-06 18:15:29.000 +05:30),
            |store, plan| {
                store
                    .set_commit(plan, 1, "0123456789abcdef0123456789abcdef01234567")
                    .unwrap()
            },
        );
    }

    #[test]
    fn add_deviation_appends_a_typed_entry() {
        assert_step(
            "step-04-status-de-risked.xml",
            "step-05-deviation-minor.xml",
            time::macros::datetime!(2026-08-06 18:15:30.060 +05:30),
            |store, plan| {
                store.add_deviation(plan, 1, "minor", "Split parsing into two passes").unwrap()
            },
        );
    }

    #[test]
    fn project_update_appends_an_entry_defaulting_blocked_to_false() {
        assert_step(
            "step-12-comment-appends.xml",
            "step-13-update-message.xml",
            time::macros::datetime!(2026-08-06 18:15:35.490 +05:30),
            |store, plan| {
                store.project_update(plan, None, None, Some("Mid-plan checkpoint")).unwrap()
            },
        );
    }

    #[test]
    fn project_update_records_a_blocked_entry_without_touching_plan_status() {
        assert_step(
            "step-13-update-message.xml",
            "step-14-update-blocked.xml",
            time::macros::datetime!(2026-08-06 18:15:36.202 +05:30),
            |store, plan| {
                store
                    .project_update(plan, None, Some(true), Some("Blocked on format decision"))
                    .unwrap()
            },
        );
    }

    #[test]
    fn project_update_with_a_status_also_rewrites_plan_status() {
        assert_step(
            "step-14-update-blocked.xml",
            "step-15-update-in-review.xml",
            time::macros::datetime!(2026-08-06 18:15:36.867 +05:30),
            |store, plan| {
                store
                    .project_update(plan, Some("in-review"), None, Some("Ready for review"))
                    .unwrap()
            },
        );
    }

    #[test]
    fn add_task_without_risk_or_depends_on_renders_the_bare_element() {
        assert_step(
            "step-09-add-task-with-deps.xml",
            "step-10-add-task-minimal.xml",
            time::macros::datetime!(2026-08-06 18:15:33.000 +05:30),
            |store, plan| {
                assert_eq!(
                    store.add_task(plan, &spec(0, "Bare addition", "", ""), "plan-42-v1").unwrap(),
                    5
                );
            },
        );
    }

    #[test]
    fn get_task_name_falls_back_to_the_stringified_id() {
        let plan = PlanTasks::parse(&gw_fixture("step-00-init.xml")).unwrap();
        let repo = tempfile::tempdir().unwrap();
        let store = pinned_store(repo.path());
        assert_eq!(store.get_task_name(&plan, 2), "Write the output");
        assert_eq!(store.get_task_name(&plan, 99), "99");
    }

    #[test]
    fn mutations_report_a_missing_task_with_the_java_message() {
        let repo = tempfile::tempdir().unwrap();
        let store = pinned_store(repo.path());
        let mut plan = PlanTasks::parse(&gw_fixture("step-00-init.xml")).unwrap();

        let failures = [
            store.update_task_status(&mut plan, 99, "closed").unwrap_err(),
            store.add_comment(&mut plan, 99, "nope").unwrap_err(),
            store.add_deviation(&mut plan, 99, "minor", "nope").unwrap_err(),
            store.set_commit(&mut plan, 99, "abc").unwrap_err(),
            store.set_depends_on(&mut plan, 99, "1").unwrap_err(),
            store.get_depends_on(&plan, 99).unwrap_err(),
        ];
        for err in failures {
            assert_eq!(err.to_string(), "Task 99 not found");
        }
    }

    #[test]
    fn invalid_enum_tokens_are_rejected_on_the_way_in() {
        let repo = tempfile::tempdir().unwrap();
        let store = pinned_store(repo.path());
        let mut plan = PlanTasks::parse(&gw_fixture("step-00-init.xml")).unwrap();

        assert_eq!(
            store.update_task_status(&mut plan, 1, "nonsense").unwrap_err().to_string(),
            "invalid task status 'nonsense'"
        );
        assert_eq!(
            store.add_deviation(&mut plan, 1, "sideways", "m").unwrap_err().to_string(),
            "invalid deviation type 'sideways'"
        );
        assert_eq!(
            store.project_update(&mut plan, Some("dormant"), None, None).unwrap_err().to_string(),
            "invalid plan status 'dormant'"
        );
        // Rejected input leaves the document untouched.
        assert_eq!(plan.to_xml(), gw_fixture("step-00-init.xml"));
    }

    #[test]
    fn with_clock_pins_the_mutation_timestamp() {
        let repo = tempfile::tempdir().unwrap();
        let locator = ShipsmoothDataLocator::in_repo(repo.path()).unwrap();
        let store = TaskStore::with_clock(locator, || {
            time::macros::datetime!(2026-08-06 12:00:00.123 +05:30)
        });
        assert_eq!(store.now_xml_date_time(), "2026-08-06T12:00:00.123+05:30");
    }

    #[test]
    fn default_clock_is_the_system_clock() {
        let repo = tempfile::tempdir().unwrap();
        let store = store_in(repo.path());

        // The lexical form is fixed-width and, at a constant offset, sorts by
        // instant — so bracketing the call pins it to the live clock without
        // re-parsing or asserting on a wall-clock value.
        let before = xml_time::xml_date_time(xml_time::system_now());
        let stamped = store.now_xml_date_time();
        let after = xml_time::xml_date_time(xml_time::system_now());
        assert!(before <= stamped && stamped <= after, "{before} <= {stamped} <= {after}");
    }
}
