//! Port of `io.bitken.ss.gw.GitTags`: resolves and creates plan-version tags
//! (`plan-{N}-v*`).
//!
//! Every git command runs in the configured `work_dir` (the repo root).
//! Running them in the process's inherited CWD is what broke tagging whenever
//! the CLI was invoked from anywhere but the repo root — plan-70 defect B, and
//! the reason `GitTagsIntegrationTest` deliberately runs from a foreign CWD.
//!
//! Unlike [`super::GitState`], nothing here reports: every failure — a missing
//! repo, an unlaunchable git, an existing tag — degrades to a safe default.

use std::path::{Path, PathBuf};
use std::process::Command;

pub struct GitTags {
    work_dir: PathBuf,
}

impl GitTags {
    pub fn new(work_dir: &Path) -> GitTags {
        GitTags { work_dir: work_dir.to_path_buf() }
    }

    /// The highest-numbered `plan-{N}-v*` tag, or `plan-{N}-v1` when there is
    /// no tag and when git is unavailable.
    pub fn get_plan_version(&self, plan_num: u32) -> String {
        self.highest_version_tag(plan_num).unwrap_or_else(|| format!("{}1", version_prefix(plan_num)))
    }

    /// The next version tag: `v1` when no version tag exists yet, `v{K+1}`
    /// when the highest is `vK`. Keeping "no tag" distinct from "v1" is the
    /// whole reason [`Self::highest_version_tag`] returns an `Option`.
    pub fn next_plan_version(&self, plan_num: u32) -> String {
        let next = match self.highest_version_tag(plan_num).as_deref().and_then(version_number) {
            Some(highest) => highest + 1,
            None => 1,
        };
        format!("{}{next}", version_prefix(plan_num))
    }

    /// True when the tag exists locally.
    pub fn tag_exists(&self, tag: &str) -> bool {
        self.first_line(&["tag", "-l", tag]).is_some()
    }

    /// Creates a local tag at HEAD. True on success — notably false when the
    /// tag already exists, since git exits non-zero.
    pub fn create_tag(&self, tag: &str) -> bool {
        Command::new("git")
            .args(["tag", tag])
            .current_dir(&self.work_dir)
            .output()
            .is_ok_and(|out| out.status.success())
    }

    /// The highest-numbered `plan-{N}-v*` tag, or `None` when none exists or
    /// git is unavailable. Sorting is git's own `version:refname`, and only
    /// the first line is read — as Java's single `readLine` does.
    fn highest_version_tag(&self, plan_num: u32) -> Option<String> {
        let glob = format!("{}*", version_prefix(plan_num));
        // The glob anchors on the plan number, so plan-90's tags can never
        // satisfy plan-9's query.
        self.first_line(&["tag", "-l", &glob, "--sort=-version:refname"])
    }

    /// First non-blank line of the command's stdout, or `None` when there is
    /// none or git could not run at all.
    fn first_line(&self, args: &[&str]) -> Option<String> {
        let out = Command::new("git").args(args).current_dir(&self.work_dir).output().ok()?;
        let stdout = String::from_utf8_lossy(&out.stdout);
        let first = stdout.lines().next()?.trim();
        (!first.is_empty()).then(|| first.to_string())
    }
}

fn version_prefix(plan_num: u32) -> String {
    format!("plan-{plan_num}-v")
}

/// The `K` in a `plan-{N}-vK` tag.
///
/// Java parses the suffix after the last `-v` and trusts the glob to make it
/// numeric, which `plan-7-v1-rc` would violate — there it throws. Returning
/// `None` instead keeps GitTags' everything-degrades contract: an unparseable
/// highest tag derives `v1` rather than crashing the command.
fn version_number(tag: &str) -> Option<u32> {
    tag.rfind("-v").and_then(|at| tag[at + 2..].parse().ok())
}

#[cfg(test)]
mod tests {
    //! Tests ported from `GitTagsIntegrationTest.java`. As there, the process
    //! CWD is the crate directory and never the temp repo, so every case only
    //! passes if GitTags runs git in its configured work_dir.

    use super::*;
    use tempfile::TempDir;

    fn fresh_repo() -> (TempDir, GitTags) {
        let repo = tempfile::tempdir().unwrap();
        let dir = repo.path();
        git(dir, &["init"]);
        git(dir, &["config", "user.email", "test@test.com"]);
        git(dir, &["config", "user.name", "Test"]);
        std::fs::write(dir.join("README.md"), "init").unwrap();
        git(dir, &["add", "."]);
        git(dir, &["commit", "-m", "init"]);
        let tags = GitTags::new(dir);
        (repo, tags)
    }

    fn git(dir: &Path, args: &[&str]) {
        let out = Command::new("git").args(args).current_dir(dir).output().unwrap();
        assert!(out.status.success(), "git {args:?}: {}", String::from_utf8_lossy(&out.stderr));
    }

    #[test]
    fn creates_a_version_tag_although_the_process_cwd_is_not_the_repo_root() {
        let (_repo, tags) = fresh_repo();
        assert!(tags.create_tag("plan-70-v1"), "create_tag must succeed from a foreign CWD");
        assert!(tags.tag_exists("plan-70-v1"), "the tag must actually exist after creation");
    }

    #[test]
    fn the_first_plan_version_is_v1_not_v2() {
        let (_repo, tags) = fresh_repo();
        assert_eq!(tags.next_plan_version(70), "plan-70-v1");
    }

    #[test]
    fn next_plan_version_increments_the_highest_existing_tag() {
        let (repo, tags) = fresh_repo();
        git(repo.path(), &["tag", "plan-70-v1"]);
        git(repo.path(), &["tag", "plan-70-v2"]);
        assert_eq!(tags.next_plan_version(70), "plan-70-v3");
    }

    #[test]
    fn get_plan_version_defaults_to_v1_when_there_are_no_tags() {
        let (_repo, tags) = fresh_repo();
        assert_eq!(tags.get_plan_version(70), "plan-70-v1");
    }

    #[test]
    fn get_plan_version_returns_the_highest_existing_tag() {
        let (repo, tags) = fresh_repo();
        git(repo.path(), &["tag", "plan-70-v1"]);
        git(repo.path(), &["tag", "plan-70-v2"]);
        assert_eq!(tags.get_plan_version(70), "plan-70-v2");
    }

    #[test]
    fn tag_exists_is_false_when_absent() {
        let (_repo, tags) = fresh_repo();
        assert!(!tags.tag_exists("plan-70-v9"));
    }

    #[test]
    fn create_tag_is_false_when_the_tag_already_exists() {
        let (_repo, tags) = fresh_repo();
        assert!(tags.create_tag("plan-70-v1"));
        assert!(!tags.create_tag("plan-70-v1"), "git exits non-zero re-creating a tag");
    }

    #[test]
    fn operations_return_safe_defaults_when_work_dir_is_not_a_git_repo() {
        let non_repo = tempfile::tempdir().unwrap();
        let tags = GitTags::new(non_repo.path());
        assert_eq!(tags.get_plan_version(70), "plan-70-v1");
        assert_eq!(tags.next_plan_version(70), "plan-70-v1");
        assert!(!tags.tag_exists("plan-70-v1"));
        assert!(!tags.create_tag("plan-70-v1"));
    }

    #[test]
    fn operations_return_safe_defaults_when_git_cannot_be_launched() {
        let repo = tempfile::tempdir().unwrap();
        let tags = GitTags::new(&repo.path().join("does-not-exist"));
        assert_eq!(tags.get_plan_version(70), "plan-70-v1");
        assert_eq!(tags.next_plan_version(70), "plan-70-v1");
        assert!(!tags.tag_exists("plan-70-v1"));
        assert!(!tags.create_tag("plan-70-v1"));
    }

    // ---- version derivation ----

    #[test]
    fn derivation_follows_the_highest_tag_not_the_tag_count() {
        let (repo, tags) = fresh_repo();
        git(repo.path(), &["tag", "plan-9-v1"]);
        git(repo.path(), &["tag", "plan-9-v4"]);
        assert_eq!(tags.get_plan_version(9), "plan-9-v4");
        assert_eq!(tags.next_plan_version(9), "plan-9-v5");
    }

    #[test]
    fn double_digit_versions_sort_above_single_digit_ones() {
        let (repo, tags) = fresh_repo();
        // Lexical sorting would put v9 on top; git's version sort must not.
        git(repo.path(), &["tag", "plan-9-v9"]);
        git(repo.path(), &["tag", "plan-9-v10"]);
        assert_eq!(tags.get_plan_version(9), "plan-9-v10");
        assert_eq!(tags.next_plan_version(9), "plan-9-v11");
    }

    #[test]
    fn another_plans_tags_never_leak_into_this_plans_derivation() {
        let (repo, tags) = fresh_repo();
        git(repo.path(), &["tag", "plan-90-v9"]);
        git(repo.path(), &["tag", "plan-9-v1"]);
        assert_eq!(tags.get_plan_version(9), "plan-9-v1");
        assert_eq!(tags.next_plan_version(9), "plan-9-v2");
        // ...and a plan with no tags of its own is unaffected by the others.
        assert_eq!(tags.next_plan_version(7), "plan-7-v1");
    }

    #[test]
    fn an_unparseable_highest_tag_derives_v1_rather_than_panicking() {
        assert_eq!(version_number("plan-7-v3"), Some(3));
        assert_eq!(version_number("plan-7-v1-rc"), None, "Java throws here; the port degrades");
        assert_eq!(version_number("plan-7"), None);
    }
}
