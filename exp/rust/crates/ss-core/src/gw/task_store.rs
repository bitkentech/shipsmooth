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
use crate::model::PlanTasks;
use crate::Result;

pub struct TaskStore {
    locator: ShipsmoothDataLocator,
    // dead_code: no mutation consumes the clock until the Task 4 port lands.
    #[allow(dead_code)]
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
    #[allow(dead_code)] // first mutation caller arrives with the Task 4 port
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
        // Shape only — the value is the current instant.
        assert!(store.now_xml_date_time().contains('T'));
    }
}
