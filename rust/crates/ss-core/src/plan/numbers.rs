//! Port of `PlanNumbers`: reads the plans directory to answer "what is the
//! next plan id". The Java version derives the directory and the
//! `plan-N.md` pattern from `ShipsmoothDataLocator`; until the conf port
//! lands, the directory is passed in directly and the pattern lives here.

use std::path::PathBuf;

use regex::Regex;

use crate::Result;

pub struct PlanNumbers {
    plans_dir: PathBuf,
    plan_file: Regex,
}

impl PlanNumbers {
    pub fn new(plans_dir: PathBuf) -> Self {
        PlanNumbers {
            plans_dir,
            // Java: Pattern.quote("plan-") + "(\\d+)" + Pattern.quote(".md"),
            // used with Matcher.matches() — i.e. a full-name match.
            plan_file: Regex::new(r"^plan-(\d+)\.md$").unwrap(),
        }
    }

    /// The next plan id: highest existing `plan-N.md` + 1, or 1 if none.
    pub fn next(&self) -> Result<u32> {
        Ok(self.highest_existing()? + 1)
    }

    fn highest_existing(&self) -> Result<u32> {
        if !self.plans_dir.is_dir() {
            return Ok(0);
        }
        let mut highest = 0;
        for entry in std::fs::read_dir(&self.plans_dir)? {
            let name = entry?.file_name();
            if let Some(caps) = self.plan_file.captures(&name.to_string_lossy()) {
                highest = highest.max(caps[1].parse().unwrap_or(0));
            }
        }
        Ok(highest)
    }
}

// Tests ported verbatim from PlanNumbersTest.java (the locator indirection is
// replaced by passing `<repo>/.shipsmooth/plans` directly).
#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::Path;

    fn plan_numbers(repo_root: &Path) -> PlanNumbers {
        PlanNumbers::new(repo_root.join(".shipsmooth/plans"))
    }

    fn write_plans(repo_root: &Path, names: &[&str]) {
        let plans = repo_root.join(".shipsmooth/plans");
        fs::create_dir_all(&plans).unwrap();
        for name in names {
            fs::write(plans.join(name), "x").unwrap();
        }
    }

    #[test]
    fn returns_one_when_plans_dir_absent() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(plan_numbers(dir.path()).next().unwrap(), 1);
    }

    #[test]
    fn returns_one_when_plans_dir_empty() {
        let dir = tempfile::tempdir().unwrap();
        fs::create_dir_all(dir.path().join(".shipsmooth/plans")).unwrap();
        assert_eq!(plan_numbers(dir.path()).next().unwrap(), 1);
    }

    #[test]
    fn returns_max_plus_one() {
        let dir = tempfile::tempdir().unwrap();
        write_plans(dir.path(), &["plan-1.md", "plan-2.md", "plan-3.md"]);
        assert_eq!(plan_numbers(dir.path()).next().unwrap(), 4);
    }

    #[test]
    fn uses_max_not_count_across_gaps() {
        let dir = tempfile::tempdir().unwrap();
        write_plans(dir.path(), &["plan-1.md", "plan-5.md"]);
        assert_eq!(plan_numbers(dir.path()).next().unwrap(), 6);
    }

    #[test]
    fn ignores_non_plan_and_tasks_files() {
        let dir = tempfile::tempdir().unwrap();
        write_plans(dir.path(), &["plan-2.md", "plan-2-tasks.xml", "README.md", "notes.txt"]);
        assert_eq!(plan_numbers(dir.path()).next().unwrap(), 3);
    }
}
