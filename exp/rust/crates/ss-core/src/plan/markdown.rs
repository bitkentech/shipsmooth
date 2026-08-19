//! Port of `PlanMarkdownParser`: `plan-{N}.md` narrative markdown →
//! [`ParsedTask`] records. The regex patterns are the spec (they are stable
//! while the XML schema evolves on a different cadence).

/// One `### Task N: name [Risk]` heading with its optional
/// `*Depends-on: 1,2*` line.
#[derive(Debug, PartialEq, Eq)]
pub struct ParsedTask {
    pub id: u32,
    pub name: String,
    /// Lowercased, or `""` when the heading has no risk tag.
    pub risk: String,
    /// Comma-separated ids with whitespace stripped, or `""`.
    pub depends_on: String,
}

use std::sync::LazyLock;

use regex::Regex;

static HEADING: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?im)^###\s+Task\s+(\d+):\s+(.+?)(?:\s+\[(High|Medium|Low)\])?\s*$").unwrap()
});

/// Matches an optional `*Depends-on: 1,2,3*` line anywhere after the heading.
static DEPENDS_ON: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"(?im)^\*Depends-on:\s*([\d,\s]+)\*\s*$").unwrap());

/// The Java parser caps each task's search region at heading end + 500 —
/// a depends-on line beyond the window is silently dropped (PB-352).
const REGION_CAP: usize = 500;

pub fn parse_tasks(markdown: &str) -> Vec<ParsedTask> {
    let mut tasks = Vec::new();
    for caps in HEADING.captures_iter(markdown) {
        let heading_end = caps.get(0).unwrap().end();
        let region = task_region(markdown, heading_end);
        tasks.push(ParsedTask {
            // Java parses with Integer.parseInt and lets overflow throw
            // unchecked; the panic here is the same contract.
            id: caps[1].parse().expect("task id out of range"),
            name: caps[2].trim().to_owned(),
            risk: caps.get(3).map(|r| r.as_str().to_lowercase()).unwrap_or_default(),
            depends_on: depends_on_in(region),
        });
    }
    tasks
}

/// The slice after a heading in which its depends-on line may appear: capped
/// at [`REGION_CAP`] and at the next task heading, whichever comes first.
fn task_region(markdown: &str, heading_end: usize) -> &str {
    let mut cap = usize::min(heading_end + REGION_CAP, markdown.len());
    while !markdown.is_char_boundary(cap) {
        cap -= 1;
    }
    let capped = &markdown[heading_end..cap];
    match HEADING.find(capped) {
        Some(next) => &capped[..next.start()],
        None => capped,
    }
}

fn depends_on_in(region: &str) -> String {
    DEPENDS_ON
        .captures(region)
        .map(|c| c[1].chars().filter(|ch| !ch.is_whitespace()).collect())
        .unwrap_or_default()
}

// No dedicated Java unit test file exists for the parser; these pin the
// behaviours the Java regexes define and the fixture corpus exercises
// (fixtures/xml/01-fresh-init.xml is `plan init` over this same syntax).
#[cfg(test)]
mod tests {
    use super::*;

    // ---- slice_task_section ----

    const THREE_TASKS: &str = "\
## Tasks

### Task 1: Parse the input [High]

*Depends-on: none*

Body of one.

### Task 2: Write the output [Medium]

Body of two.

### Task 10: Wire it up [Low]

Body of ten.
";

    #[test]
    fn slices_from_the_heading_to_the_next_task_heading() {
        let section = slice_task_section(THREE_TASKS, 1);
        assert!(section.starts_with("### Task 1: Parse the input [High]"));
        assert!(section.ends_with("Body of one."), "trailing blank lines are trimmed: {section:?}");
        assert!(!section.contains("Task 2"));
    }

    #[test]
    fn slices_the_last_task_to_the_end_of_the_file() {
        let section = slice_task_section(THREE_TASKS, 10);
        assert!(section.starts_with("### Task 10: Wire it up [Low]"));
        assert!(section.ends_with("Body of ten."));
    }

    #[test]
    fn a_task_id_is_matched_whole_not_as_a_prefix() {
        // "### Task 1:" must not match the "### Task 10:" heading — the colon
        // is what anchors it.
        let only_ten = "### Task 10: Wire it up [Low]\n\nBody of ten.\n";
        assert_eq!(slice_task_section(only_ten, 1), "");
    }

    #[test]
    fn an_absent_task_slices_to_nothing() {
        assert_eq!(slice_task_section(THREE_TASKS, 99), "");
        assert_eq!(slice_task_section("", 1), "");
    }


    #[test]
    fn parses_heading_with_risk_and_lowercases_it() {
        let tasks = parse_tasks("### Task 1: Parse the input file [High]\n\nBody.\n");
        assert_eq!(
            tasks,
            vec![ParsedTask {
                id: 1,
                name: "Parse the input file".into(),
                risk: "high".into(),
                depends_on: String::new(),
            }]
        );
    }

    #[test]
    fn heading_without_risk_tag_yields_empty_risk() {
        let tasks = parse_tasks("### Task 4: Document the format\n");
        assert_eq!(tasks[0].risk, "");
        assert_eq!(tasks[0].name, "Document the format");
    }

    #[test]
    fn heading_match_is_case_insensitive() {
        let tasks = parse_tasks("### task 2: shout Quietly [low]\n");
        assert_eq!(tasks[0].id, 2);
        assert_eq!(tasks[0].risk, "low");
    }

    #[test]
    fn depends_on_strips_whitespace_between_ids() {
        let md = "### Task 3: Wire it [Low]\n\n*Depends-on: 1, 2*\n\nBody.\n";
        assert_eq!(parse_tasks(md)[0].depends_on, "1,2");
    }

    // Regression for the plan-102 authoring bug: `*Depends-on:* 1` (closing
    // asterisk after the colon) must NOT match — only `*Depends-on: 1*` does.
    #[test]
    fn wrongly_italicized_depends_on_is_ignored() {
        let md = "### Task 2: Write it [Medium]\n\n*Depends-on:* 1\n";
        assert_eq!(parse_tasks(md)[0].depends_on, "");
    }

    // PB-352: the search region is capped ~500 chars after the heading; a
    // depends-on line beyond it is silently dropped.
    #[test]
    fn depends_on_beyond_the_500_char_window_is_dropped() {
        let md = format!(
            "### Task 2: Long body [Low]\n\n{}\n*Depends-on: 1*\n",
            "x".repeat(600)
        );
        assert_eq!(parse_tasks(&md)[0].depends_on, "");
    }

    #[test]
    fn depends_on_belongs_to_its_own_task_region() {
        let md = "### Task 1: First [High]\n\nBody.\n\n### Task 2: Second [Low]\n\n*Depends-on: 1*\n";
        let tasks = parse_tasks(md);
        assert_eq!(tasks[0].depends_on, "");
        assert_eq!(tasks[1].depends_on, "1");
    }
}
