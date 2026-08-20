//! `plan tag --plan N --kind version|complete|abandoned`
//!
//! Port of the Java `Tag`. Creates the local git tag and prints the push
//! line; it never pushes. For `version` the next vK is derived from the git
//! tags themselves (`GitTags::next_plan_version`) and refused if it already
//! exists.
//!
//! Note: 02-cli.md recorded a defect here — "derives the version from the XML
//! field, not git tags". That is stale: the Java `Tag` never reads the XML
//! (verified against the source). There is no defect to preserve.

use ss_core::gw::GitTags;

const FIXED_KINDS: [&str; 2] = ["complete", "abandoned"];

pub fn run(git_tags: &GitTags, plan: u32, kind: &str) -> i32 {
    match kind {
        "version" => create_version_tag(git_tags, plan),
        k if FIXED_KINDS.contains(&k) => create_and_print(git_tags, &format!("plan-{plan}-{k}")),
        _ => {
            println!("ERROR: --kind must be one of: version, complete, abandoned");
            1
        }
    }
}

fn create_version_tag(git_tags: &GitTags, plan: u32) -> i32 {
    let tag = git_tags.next_plan_version(plan);
    // Defensive, and unreachable in practice: next_plan_version returns
    // highest + 1, so the tag it names cannot already exist. Java carries the
    // same guard, so it is ported rather than dropped — but nothing can test
    // it without reaching past the gateway.
    if git_tags.tag_exists(&tag) {
        println!("ERROR: tag {tag} already exists — commit more changes before re-tagging");
        return 1;
    }
    create_and_print(git_tags, &tag)
}

fn create_and_print(git_tags: &GitTags, tag: &str) -> i32 {
    if !git_tags.create_tag(tag) {
        println!("ERROR: failed to create tag {tag}");
        return 1;
    }
    println!("Created tag: {tag}");
    println!("Run: git push origin {tag}");
    0
}
